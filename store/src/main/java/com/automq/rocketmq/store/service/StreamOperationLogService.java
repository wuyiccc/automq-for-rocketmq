/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.rocketmq.store.service;

import com.automq.rocketmq.common.config.StoreConfig;
import com.automq.rocketmq.store.api.MessageStateMachine;
import com.automq.rocketmq.store.api.StreamStore;
import com.automq.rocketmq.store.exception.StoreException;
import com.automq.rocketmq.store.model.operation.AckOperation;
import com.automq.rocketmq.store.model.operation.ChangeInvisibleDurationOperation;
import com.automq.rocketmq.store.model.operation.Operation;
import com.automq.rocketmq.store.model.operation.PopOperation;
import com.automq.rocketmq.store.model.stream.SingleRecord;
import com.automq.rocketmq.store.service.api.OperationLogService;
import com.automq.rocketmq.store.util.SerializeUtil;
import com.automq.stream.api.AppendResult;
import com.automq.stream.api.RecordBatchWithContext;
import com.automq.stream.utils.FutureUtil;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamOperationLogService implements OperationLogService {
    public static final Logger LOGGER = LoggerFactory.getLogger(StreamOperationLogService.class);
    private final StreamStore streamStore;
    private final SnapshotService snapshotService;
    private final StoreConfig storeConfig;

    public StreamOperationLogService(StreamStore streamStore, SnapshotService snapshotService,
        StoreConfig storeConfig) {
        this.streamStore = streamStore;
        this.snapshotService = snapshotService;
        this.storeConfig = storeConfig;
    }

    @Override
    public CompletableFuture<Void> recover(MessageStateMachine stateMachine, long operationStreamId,
        long snapshotStreamId) {
        // 1. get snapshot
        SnapshotService.SnapshotStatus snapshotStatus = snapshotService.getSnapshotStatus(stateMachine.topicId(), stateMachine.queueId());
        long snapStartOffset = streamStore.startOffset(snapshotStreamId);
        snapshotStatus.snapshotEndOffset().set(streamStore.nextOffset(snapshotStreamId));
        long startOffset = streamStore.startOffset(operationStreamId);
        snapshotStatus.operationStartOffset().set(startOffset);
        long endOffset = streamStore.nextOffset(operationStreamId);
        CompletableFuture<Long/*op replay start offset*/> snapshotFetch;
        long snapEndOffset = snapshotStatus.snapshotEndOffset().get();
        if (snapStartOffset == snapEndOffset) {
            // no snapshot
            snapshotFetch = CompletableFuture.completedFuture(startOffset);
        } else {
            snapshotFetch = streamStore.fetch(snapshotStreamId, snapEndOffset - 1, 1)
                .thenApply(result -> SerializeUtil.decodeOperationSnapshot(result.recordBatchList().get(0).rawPayload()))
                .thenApply(snapshot -> {
                    try {
                        stateMachine.loadSnapshot(snapshot);
                    } catch (Exception e) {
                        // TODO: handle exception
                        throw new RuntimeException(e);
                    }
                    return snapshot.getSnapshotEndOffset() + 1;
                });
        }
        // 2. get all operations
        // TODO: batch fetch, fetch all at once may cause some problems
        return snapshotFetch.thenCompose(offset -> streamStore.fetch(operationStreamId, offset, (int) (endOffset - offset))
            .thenAccept(result -> {
                // load operations
                for (RecordBatchWithContext batchWithContext : result.recordBatchList()) {
                    // TODO: assume that a batch only contains one operation
                    Operation operation = SerializeUtil.decodeOperation(batchWithContext.rawPayload(), stateMachine,
                        operationStreamId, snapshotStreamId);
                    try {
                        // TODO: operation may be null
                        replay(batchWithContext.baseOffset(), operation);
                    } catch (StoreException e) {
                        throw new CompletionException(e);
                    }
                }
            }));
    }

    @Override
    public CompletableFuture<Long> logPopOperation(PopOperation operation) {
        return streamStore.append(operation.operationStreamId(),
                new SingleRecord(ByteBuffer.wrap(SerializeUtil.encodePopOperation(operation))))
            .thenApply(result -> doReplay(result, operation));
    }

    @Override
    public CompletableFuture<Long> logAckOperation(AckOperation operation) {
        return streamStore.append(operation.operationStreamId(),
                new SingleRecord(ByteBuffer.wrap(SerializeUtil.encodeAckOperation(operation))))
            .thenApply(result -> doReplay(result, operation));
    }

    @Override
    public CompletableFuture<Long> logChangeInvisibleDurationOperation(ChangeInvisibleDurationOperation operation) {
        return streamStore.append(operation.operationStreamId(),
                new SingleRecord(ByteBuffer.wrap(SerializeUtil.encodeChangeInvisibleDurationOperation(operation))))
            .thenApply(result -> doReplay(result, operation));
    }

    private void notifySnapshot(Operation operation) {
        MessageStateMachine stateMachine = operation.stateMachine();
        SnapshotService.SnapshotStatus snapshotStatus = snapshotService.getSnapshotStatus(stateMachine.topicId(), stateMachine.queueId());
        if (snapshotStatus.takingSnapshot().compareAndSet(false, true)) {
            CompletableFuture<SnapshotService.TakeSnapshotResult> taskCf = snapshotService.addSnapshotTask(new SnapshotService.SnapshotTask(
                operation.topicId(), operation.queueId(), operation.operationStreamId(), operation.snapshotStreamId(),
                stateMachine::takeSnapshot));
            taskCf.thenAccept(takeSnapshotResult -> {
                snapshotStatus.takingSnapshot().set(false);
                if (takeSnapshotResult.success()) {
                    snapshotStatus.operationStartOffset().set(takeSnapshotResult.newOpStartOffset());
                } else {
                    LOGGER.warn("Topic {}, queue: {}: Take snapshot task is aborted", operation.topicId(), operation.queueId());
                }
            }).exceptionally(e -> {
                Throwable cause = FutureUtil.cause(e);
                LOGGER.error("Topic {}, queue: {}: Take snapshot failed", operation.topicId(), operation.queueId(), cause);
                snapshotStatus.takingSnapshot().set(false);
                return null;
            });
        }
    }

    private long doReplay(AppendResult result, Operation operation) {
        try {
            replay(result.baseOffset(), operation);
        } catch (StoreException e) {
            LOGGER.error("Topic {}, queue: {}: Replay operation:{} failed", operation.topicId(), operation.queueId(), operation, e);
            throw new CompletionException(e);
        }

        MessageStateMachine stateMachine = operation.stateMachine();
        SnapshotService.SnapshotStatus snapshotStatus = snapshotService.getSnapshotStatus(stateMachine.topicId(), stateMachine.queueId());
        if (result.baseOffset() - snapshotStatus.operationStartOffset().get() + 1 >= storeConfig.operationSnapshotInterval()) {
            notifySnapshot(operation);
        }
        return result.baseOffset();
    }

    private void replay(long operationOffset, Operation operation) throws StoreException {
        // TODO: optimize concurrent control
        switch (operation.operationType()) {
            case POP -> operation.stateMachine().replayPopOperation(operationOffset, (PopOperation) operation);
            case ACK -> operation.stateMachine().replayAckOperation(operationOffset, (AckOperation) operation);
            case CHANGE_INVISIBLE_DURATION ->
                operation.stateMachine().replayChangeInvisibleDurationOperation(operationOffset, (ChangeInvisibleDurationOperation) operation);
            default -> throw new IllegalStateException("Unexpected value: " + operation.operationType());
        }
    }
}
