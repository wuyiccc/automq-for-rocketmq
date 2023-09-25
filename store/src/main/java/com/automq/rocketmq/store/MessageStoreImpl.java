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

package com.automq.rocketmq.store;

import com.automq.rocketmq.common.config.StoreConfig;
import com.automq.rocketmq.common.model.MessageExt;
import com.automq.rocketmq.common.model.generated.Message;
import com.automq.rocketmq.metadata.StoreMetadataService;
import com.automq.rocketmq.store.api.MessageStore;
import com.automq.rocketmq.store.api.StreamStore;
import com.automq.rocketmq.store.exception.StoreException;
import com.automq.rocketmq.store.model.generated.CheckPoint;
import com.automq.rocketmq.store.model.generated.ReceiptHandle;
import com.automq.rocketmq.store.model.kv.BatchDeleteRequest;
import com.automq.rocketmq.store.model.kv.BatchRequest;
import com.automq.rocketmq.store.model.kv.BatchWriteRequest;
import com.automq.rocketmq.store.model.message.AckResult;
import com.automq.rocketmq.store.model.message.ChangeInvisibleDurationResult;
import com.automq.rocketmq.store.model.message.Filter;
import com.automq.rocketmq.store.model.message.PopResult;
import com.automq.rocketmq.store.model.message.PutResult;
import com.automq.rocketmq.store.model.stream.SingleRecord;
import com.automq.rocketmq.store.service.api.KVService;
import com.automq.rocketmq.store.service.api.OperationLogService;
import com.automq.rocketmq.store.service.InflightService;
import com.automq.rocketmq.store.service.ReviveService;
import com.automq.rocketmq.store.util.SerializeUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.tuple.Pair;

import static com.automq.rocketmq.store.util.SerializeUtil.buildCheckPointKey;
import static com.automq.rocketmq.store.util.SerializeUtil.buildCheckPointValue;
import static com.automq.rocketmq.store.util.SerializeUtil.buildOrderIndexKey;
import static com.automq.rocketmq.store.util.SerializeUtil.buildOrderIndexValue;
import static com.automq.rocketmq.store.util.SerializeUtil.buildTimerTagKey;
import static com.automq.rocketmq.store.util.SerializeUtil.buildTimerTagValue;
import static com.automq.rocketmq.store.util.SerializeUtil.decodeReceiptHandle;

public class MessageStoreImpl implements MessageStore {
    protected static final String KV_NAMESPACE_CHECK_POINT = "check_point";
    protected static final String KV_NAMESPACE_TIMER_TAG = "timer_tag";
    protected static final String KV_NAMESPACE_FIFO_INDEX = "fifo_index";

    StoreConfig config;

    private final StreamStore streamStore;

    private final OperationLogService operationLogService;
    private final StoreMetadataService metadataService;
    private final KVService kvService;

    private ReviveService reviveService;
    private final InflightService inflightService = new InflightService();

    public MessageStoreImpl(StoreConfig config, StreamStore streamStore, OperationLogService operationLogService,
        StoreMetadataService metadataService, KVService kvService) {
        this.config = config;
        this.streamStore = streamStore;
        this.operationLogService = operationLogService;
        this.metadataService = metadataService;
        this.kvService = kvService;
    }

    public void startReviveService() {
        reviveService = new ReviveService(KV_NAMESPACE_CHECK_POINT, KV_NAMESPACE_TIMER_TAG, kvService, metadataService, inflightService, streamStore);
        reviveService.start();
    }

    public void writeCheckPoint(long topicId, int queueId, long streamId, long offset, long consumerGroupId,
        long operationId, boolean fifo, boolean retry, long operationTimestamp,
        long nextVisibleTimestamp) throws StoreException {
        // If this message is not orderly or has not been consumed, write check point and timer tag to KV service atomically.
        List<BatchRequest> requestList = new ArrayList<>();
        BatchWriteRequest writeCheckPointRequest = new BatchWriteRequest(KV_NAMESPACE_CHECK_POINT,
            buildCheckPointKey(topicId, queueId, offset, operationId),
            buildCheckPointValue(topicId, queueId, offset, consumerGroupId, operationId, fifo, retry, operationTimestamp, nextVisibleTimestamp, 0));
        requestList.add(writeCheckPointRequest);

        BatchWriteRequest writeTimerTagRequest = new BatchWriteRequest(KV_NAMESPACE_TIMER_TAG,
            buildTimerTagKey(nextVisibleTimestamp, topicId, queueId, offset, operationId),
            buildTimerTagValue(nextVisibleTimestamp, consumerGroupId, topicId, queueId, streamId, offset, operationId));
        requestList.add(writeTimerTagRequest);

        // If this message is orderly, write order index to KV service.
        if (fifo) {
            BatchWriteRequest writeOrderIndexRequest = new BatchWriteRequest(KV_NAMESPACE_FIFO_INDEX,
                buildOrderIndexKey(consumerGroupId, topicId, queueId, offset), buildOrderIndexValue(operationId));
            requestList.add(writeOrderIndexRequest);
        }

        kvService.batch(requestList.toArray(new BatchRequest[0]));
    }

    public Optional<CheckPoint> retrieveFifoCheckPoint(long consumerGroupId, long topicId, int queueId,
        long offset) throws StoreException {
        // TODO: Undefined behavior if last operation is not orderly.
        byte[] orderIndexKey = buildOrderIndexKey(consumerGroupId, topicId, queueId, offset);
        byte[] bytes = kvService.get(KV_NAMESPACE_FIFO_INDEX, orderIndexKey);
        // If fifo index not found, this message has not been consumed.
        if (bytes == null) {
            return Optional.empty();
        }
        long lastOperationId = ByteBuffer.wrap(bytes).getLong();
        byte[] checkPoint = kvService.get(KV_NAMESPACE_CHECK_POINT, buildCheckPointKey(topicId, queueId, offset, lastOperationId));
        if (checkPoint != null) {
            return Optional.of(CheckPoint.getRootAsCheckPoint(ByteBuffer.wrap(checkPoint)));
        } else {
            // TODO: log finding a orphan index, this maybe a bug
            kvService.delete(KV_NAMESPACE_FIFO_INDEX, orderIndexKey);
        }
        return Optional.empty();
    }

    public void renewFifoCheckPoint(CheckPoint lastCheckPoint, long topicId, int queueId, long streamId, long offset,
        long consumerGroupId, long operationId, long operationTimestamp,
        long nextVisibleTimestamp) throws StoreException {
        // Delete last check point and timer tag.
        BatchDeleteRequest deleteLastCheckPointRequest = new BatchDeleteRequest(KV_NAMESPACE_CHECK_POINT,
            buildCheckPointKey(topicId, queueId, offset, lastCheckPoint.operationId()));

        BatchDeleteRequest deleteLastTimerTagRequest = new BatchDeleteRequest(KV_NAMESPACE_TIMER_TAG,
            buildTimerTagKey(lastCheckPoint.nextVisibleTimestamp(), topicId, queueId, offset, lastCheckPoint.operationId()));

        // Write new check point, timer tag, and order index.
        BatchWriteRequest writeCheckPointRequest = new BatchWriteRequest(KV_NAMESPACE_CHECK_POINT,
            buildCheckPointKey(topicId, queueId, offset, operationId),
            buildCheckPointValue(topicId, queueId, offset, consumerGroupId, operationId, true, false, operationTimestamp, nextVisibleTimestamp, lastCheckPoint.reconsumeCount() + 1));

        BatchWriteRequest writeTimerTagRequest = new BatchWriteRequest(KV_NAMESPACE_TIMER_TAG,
            buildTimerTagKey(nextVisibleTimestamp, topicId, queueId, offset, operationId),
            buildTimerTagValue(nextVisibleTimestamp, consumerGroupId, topicId, queueId, streamId, offset, operationId));

        BatchWriteRequest writeOrderIndexRequest = new BatchWriteRequest(KV_NAMESPACE_FIFO_INDEX,
            buildOrderIndexKey(consumerGroupId, topicId, queueId, offset), buildOrderIndexValue(operationId));
        kvService.batch(deleteLastCheckPointRequest, deleteLastTimerTagRequest, writeCheckPointRequest, writeTimerTagRequest, writeOrderIndexRequest);
    }

    public CompletableFuture<List<MessageExt>> fetchMessages(long streamId, long topicId, int queueId,
        long offset, int batchSize, long operationId) {
        return streamStore.fetch(streamId, offset, batchSize)
            .thenApply(fetchResult -> {
                // TODO: Assume message count is always 1 in each batch for now.
                return fetchResult.recordBatchList()
                    .stream()
                    .map(batch -> {
                        Message message = Message.getRootAsMessage(batch.rawPayload());
                        long messageOffset = batch.baseOffset();
                        String receiptHandle = SerializeUtil.encodeReceiptHandle(topicId, queueId, messageOffset, operationId);
                        return MessageExt.Builder.builder()
                            .message(message)
                            .offset(messageOffset)
                            .receiptHandle(receiptHandle)
                            .build();
                    })
                    .toList();
            });
    }

    // Fetch and filter messages until exceeding the limit.
    public CompletableFuture<List<MessageExt>> fetchAndFilterMessages(long streamId, long topicId, int queueId,
        long offset, int batchSize, int fetchBatchSize, Filter filter, List<MessageExt> messageList,
        int fetchCount, long fetchBytes, long operationTimestamp, long operationId) {
        // Fetch more messages.
        return fetchMessages(streamId, topicId, queueId, offset, fetchBatchSize, operationId)
            .thenCompose(fetchResult -> {
                // Add filter result to message list.
                messageList.addAll(filter.doFilter(fetchResult));

                // If not enough messages after applying filter, fetch more messages.
                boolean needToFetch = messageList.size() < batchSize;
                boolean hasMoreMessages = fetchResult.size() >= fetchBatchSize;

                int newFetchCount = fetchCount + fetchResult.size();
                long newFetchBytes = fetchBytes + fetchResult.stream()
                    .map(messageExt -> (long) messageExt.message().getByteBuffer().limit())
                    .reduce(0L, Long::sum);
                boolean notExceedLimit = newFetchCount < config.maxFetchCount() &&
                    newFetchBytes < config.maxFetchBytes() &&
                    System.nanoTime() - operationTimestamp < config.maxFetchTimeNanos();

                if (needToFetch && hasMoreMessages && notExceedLimit) {
                    return fetchAndFilterMessages(streamId, topicId, queueId, offset + fetchResult.size(), batchSize, fetchBatchSize, filter,
                        messageList, newFetchCount, newFetchBytes, operationTimestamp, operationId);
                } else {
                    return CompletableFuture.completedFuture(messageList);
                }
            });
    }

    @Override
    public CompletableFuture<PopResult> pop(long consumerGroupId, long topicId, int queueId, long offset, Filter filter,
        int batchSize, boolean fifo, boolean retry, long invisibleDuration) {
        if (fifo && retry) {
            return CompletableFuture.failedFuture(new RuntimeException("Fifo and retry cannot be true at the same time"));
        }

        // Write pop operation to operation log.
        // Operation id should be monotonically increasing for each queue.
        long operationTimestamp = System.nanoTime();
        CompletableFuture<Long> logOperationFuture = operationLogService.logPopOperation(consumerGroupId, topicId,
            queueId, offset, batchSize, fifo, invisibleDuration, operationTimestamp);

        long streamId;
        if (retry) {
            streamId = metadataService.getRetryStreamId(consumerGroupId, topicId, queueId);
        } else {
            streamId = metadataService.getStreamId(topicId, queueId);
        }

        int fetchBatchSize;
        if (filter.needApply()) {
            fetchBatchSize = batchSize * 2;
        } else {
            fetchBatchSize = batchSize;
        }

        CompletableFuture<List<MessageExt>> fetchAndFilterMessagesFuture =
            // Fetch messages from stream store.
            logOperationFuture.thenCompose(operationId ->
                    fetchMessages(streamId, topicId, queueId, offset, fetchBatchSize, operationId)
                        .thenApply(fetchResult -> Pair.of(fetchResult, operationId))
                )
                // Apply filter to messages.
                .thenCompose(fetchResultPair -> {
                    List<MessageExt> fetchResult = fetchResultPair.getLeft();
                    long operationId = fetchResultPair.getRight();

                    // If a filter needs to be applied, fetch more messages and apply it to messages
                    // until exceeding the limit.
                    List<MessageExt> messageList = new ArrayList<>(fetchResult);
                    if (filter.needApply()) {
                        int fetchCount = messageList.size();
                        long fetchBytes = messageList.stream()
                            .map(messageExt -> (long) messageExt.message().getByteBuffer().remaining())
                            .reduce(0L, Long::sum);

                        // Apply filter to messages
                        messageList = filter.doFilter(messageList);

                        // If not enough messages after applying filter, fetch more messages.
                        boolean needToFetch = messageList.size() < batchSize;
                        boolean hasMoreMessages = fetchResult.size() >= fetchBatchSize;
                        if (needToFetch && hasMoreMessages) {
                            return fetchAndFilterMessages(streamId, topicId, queueId, offset + fetchResult.size(), batchSize, fetchBatchSize,
                                filter, messageList, fetchCount, fetchBytes, operationTimestamp, operationId);
                        }
                    }

                    return CompletableFuture.completedFuture(messageList);
                });

        // Write check point and build pop result.
        return fetchAndFilterMessagesFuture.thenCombine(logOperationFuture, (messageList, operationId) -> {
            try {
                long nextVisibleTimestamp = operationTimestamp + invisibleDuration;

                if (messageList.size() > batchSize) {
                    messageList = messageList.subList(0, batchSize);
                }

                // If pop orderly, check whether the message is already consumed.
                Map<Long, CheckPoint> fifoCheckPointMap = new HashMap<>();
                if (fifo) {
                    for (int i = 0; i < batchSize; i++) {
                        retrieveFifoCheckPoint(consumerGroupId, topicId, queueId, offset + i)
                            .ifPresent(checkPoint -> fifoCheckPointMap.put(checkPoint.messageOffset(), checkPoint));
                    }
                }

                // Insert or renew check point and timer tag into KVService.
                for (MessageExt messageExt : messageList) {
                    // If pop orderly, the message already consumed will not trigger writing new check point.
                    // But reconsume count should be increased.
                    if (fifo && fifoCheckPointMap.containsKey(messageExt.offset())) {
                        renewFifoCheckPoint(fifoCheckPointMap.get(messageExt.offset()), topicId, queueId, streamId,
                            messageExt.offset(), consumerGroupId, operationId, operationTimestamp, nextVisibleTimestamp);
                    } else {
                        writeCheckPoint(topicId, queueId, streamId, messageExt.offset(), consumerGroupId, operationId,
                            fifo, retry, operationTimestamp, nextVisibleTimestamp);
                        inflightService.increaseInflightCount(consumerGroupId, topicId, queueId, 1);
                    }
                }

                PopResult.Status status;
                if (!messageList.isEmpty()) {
                    status = PopResult.Status.FOUND;
                } else {
                    status = PopResult.Status.NOT_FOUND;
                }
                return new PopResult(status, operationId, operationTimestamp, messageList);
            } catch (StoreException e) {
                return new PopResult(PopResult.Status.ERROR, operationId, operationTimestamp, messageList);
            }
        });
    }

    @Override
    public CompletableFuture<PutResult> put(Message message, Map<String, String> systemProperties) {
        long streamId = metadataService.getStreamId(message.topicId(), message.queueId());
        return streamStore.append(streamId, new SingleRecord(systemProperties, message.getByteBuffer()))
            .thenApply(appendResult -> new PutResult(appendResult.baseOffset()));
    }

    @Override
    public CompletableFuture<AckResult> ack(String receiptHandle) {
        // Write ack operation to operation log.
        // Operation id should be monotonically increasing for each queue
        ReceiptHandle handle = decodeReceiptHandle(receiptHandle);
        return operationLogService.logAckOperation(handle, System.nanoTime())
            .thenApply(operationId -> {
                // Delete check point and timer tag according to receiptHandle
                try {
                    // Check if check point exists.
                    byte[] checkPointKey = buildCheckPointKey(handle.topicId(), handle.queueId(), handle.messageOffset(), handle.operationId());
                    byte[] buffer = kvService.get(KV_NAMESPACE_CHECK_POINT, checkPointKey);
                    if (buffer == null) {
                        // TODO: Check point not found
                        return new AckResult(AckResult.Status.ERROR);
                    }

                    // TODO: Data race between ack and revive.
                    CheckPoint checkPoint = CheckPoint.getRootAsCheckPoint(ByteBuffer.wrap(buffer));

                    List<BatchRequest> requestList = new ArrayList<>();
                    BatchDeleteRequest deleteCheckPointRequest = new BatchDeleteRequest(KV_NAMESPACE_CHECK_POINT, checkPointKey);
                    requestList.add(deleteCheckPointRequest);

                    BatchDeleteRequest deleteTimerTagRequest = new BatchDeleteRequest(KV_NAMESPACE_TIMER_TAG,
                        buildTimerTagKey(checkPoint.nextVisibleTimestamp(), handle.topicId(), handle.queueId(), handle.messageOffset(), checkPoint.operationId()));
                    requestList.add(deleteTimerTagRequest);

                    if (checkPoint.fifo()) {
                        BatchDeleteRequest deleteOrderIndexRequest = new BatchDeleteRequest(KV_NAMESPACE_FIFO_INDEX,
                            buildOrderIndexKey(checkPoint.consumerGroupId(), handle.topicId(), handle.queueId(), checkPoint.messageOffset()));
                        requestList.add(deleteOrderIndexRequest);
                    }

                    kvService.batch(requestList.toArray(new BatchRequest[0]));
                    inflightService.decreaseInflightCount(checkPoint.consumerGroupId(), handle.topicId(), handle.queueId(), 1);
                } catch (StoreException e) {
                    return new AckResult(AckResult.Status.ERROR);
                }

                return new AckResult(AckResult.Status.SUCCESS);
            });
    }

    @Override
    public CompletableFuture<ChangeInvisibleDurationResult> changeInvisibleDuration(String receiptHandle,
        long invisibleDuration) {
        // Write change invisible duration operation to operation log.
        // Operation id should be monotonically increasing for each queue
        long operationTimestamp = System.nanoTime();
        ReceiptHandle handle = decodeReceiptHandle(receiptHandle);

        return operationLogService.logChangeInvisibleDurationOperation(handle, invisibleDuration, operationTimestamp)
            .thenApply(operationId -> {
                long nextInvisibleTimestamp = operationTimestamp + invisibleDuration;
                // change invisibleTime in check point info and regenerate timer tag
                try {
                    // Check if check point exists.
                    byte[] checkPointKey = buildCheckPointKey(handle.topicId(), handle.queueId(), handle.messageOffset(), handle.operationId());
                    byte[] buffer = kvService.get(KV_NAMESPACE_CHECK_POINT, checkPointKey);
                    if (buffer == null) {
                        // TODO: Check point not found
                        return new ChangeInvisibleDurationResult(ChangeInvisibleDurationResult.Status.ERROR);
                    }

                    // Delete last timer tag.
                    CheckPoint checkPoint = CheckPoint.getRootAsCheckPoint(ByteBuffer.wrap(buffer));

                    BatchDeleteRequest deleteLastTimerTagRequest = new BatchDeleteRequest(KV_NAMESPACE_TIMER_TAG,
                        buildTimerTagKey(checkPoint.nextVisibleTimestamp(), checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()));

                    // Write new check point and timer tag.
                    BatchWriteRequest writeCheckPointRequest = new BatchWriteRequest(KV_NAMESPACE_CHECK_POINT,
                        buildCheckPointKey(checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()),
                        buildCheckPointValue(checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(),
                            checkPoint.consumerGroupId(), checkPoint.operationId(), checkPoint.fifo(), checkPoint.retry(),
                            checkPoint.deliveryTimestamp(), nextInvisibleTimestamp, checkPoint.reconsumeCount()));

                    long streamId;
                    if (checkPoint.retry()) {
                        streamId = metadataService.getRetryStreamId(checkPoint.consumerGroupId(), checkPoint.topicId(), checkPoint.queueId());
                    } else {
                        streamId = metadataService.getStreamId(checkPoint.topicId(), checkPoint.queueId());
                    }
                    BatchWriteRequest writeTimerTagRequest = new BatchWriteRequest(KV_NAMESPACE_TIMER_TAG,
                        buildTimerTagKey(nextInvisibleTimestamp, checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()),
                        buildTimerTagValue(nextInvisibleTimestamp, checkPoint.consumerGroupId(), checkPoint.topicId(), checkPoint.queueId(),
                            streamId, checkPoint.messageOffset(), checkPoint.operationId()));

                    kvService.batch(deleteLastTimerTagRequest, writeCheckPointRequest, writeTimerTagRequest);
                } catch (StoreException e) {
                    return new ChangeInvisibleDurationResult(ChangeInvisibleDurationResult.Status.ERROR);
                }

                return new ChangeInvisibleDurationResult(ChangeInvisibleDurationResult.Status.SUCCESS);
            });
    }

    @Override
    public int getInflightStats(long consumerGroupId, long topicId, int queueId) {
        // Get check point count of specified consumer, topic and queue.
        return inflightService.getInflightCount(consumerGroupId, topicId, queueId);
    }

    @Override
    public long startOffset(long topicId, int queueId) {
        long streamId = metadataService.getStreamId(topicId, queueId);
        return streamStore.startOffset(streamId);
    }

    @Override
    public long nextOffset(long topicId, int queueId) {
        long streamId = metadataService.getStreamId(topicId, queueId);
        return streamStore.nextOffset(streamId);
    }
}