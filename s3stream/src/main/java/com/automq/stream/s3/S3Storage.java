/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.stream.s3;

import com.automq.stream.s3.cache.CacheAccessType;
import com.automq.stream.s3.cache.LogCache;
import com.automq.stream.s3.cache.ReadDataBlock;
import com.automq.stream.s3.cache.S3BlockCache;
import com.automq.stream.s3.metadata.StreamMetadata;
import com.automq.stream.s3.metrics.TimerUtil;
import com.automq.stream.s3.metrics.operations.S3Operation;
import com.automq.stream.s3.metrics.stats.OperationMetricsStats;
import com.automq.stream.s3.model.StreamRecordBatch;
import com.automq.stream.s3.objects.ObjectManager;
import com.automq.stream.s3.operator.S3Operator;
import com.automq.stream.s3.streams.StreamManager;
import com.automq.stream.s3.wal.WriteAheadLog;
import com.automq.stream.utils.FutureUtil;
import com.automq.stream.utils.ThreadUtils;
import com.automq.stream.utils.Threads;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class S3Storage implements Storage {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3Storage.class);
    private final long maxDeltaWALCacheSize;
    private final Config config;
    private final WriteAheadLog deltaWAL;
    /**
     * WAL log cache
     */
    private final LogCache deltaWALCache;
    /**
     * WAL out of order callback sequencer. Single thread mainWriteExecutor will ensure the memory safety.
     */
    private final WALCallbackSequencer callbackSequencer = new WALCallbackSequencer();
    private final Queue<DeltaWALUploadTaskContext> walPrepareQueue = new LinkedList<>();
    private final Queue<DeltaWALUploadTaskContext> walCommitQueue = new LinkedList<>();
    private final List<CompletableFuture<Void>> inflightWALUploadTasks = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService backgroundExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("s3-storage-background", true), LOGGER);
    private final ExecutorService uploadWALExecutor = Threads.newFixedThreadPoolWithMonitor(
            4, "s3-storage-upload-wal", true, LOGGER);

    private final Queue<WalWriteRequest> backoffRecords = new LinkedBlockingQueue<>();
    private final ScheduledFuture<?> drainBackoffTask;
    private long lastLogTimestamp = 0L;

    private final StreamManager streamManager;
    private final ObjectManager objectManager;
    private final S3Operator s3Operator;
    private final S3BlockCache blockCache;
    private static final int NUM_STREAM_CALLBACK_LOCKS = 128;
    /**
     * Stream callback locks. Used to ensure the stream callbacks will not be called concurrently.
     *
     * @see #handleAppendCallback
     */
    private final Lock[] streamCallbackLocks = IntStream.range(0, NUM_STREAM_CALLBACK_LOCKS).mapToObj(i -> new ReentrantLock()).toArray(Lock[]::new);

    public S3Storage(Config config, WriteAheadLog deltaWAL, StreamManager streamManager, ObjectManager objectManager,
                     S3BlockCache blockCache, S3Operator s3Operator) {
        this.config = config;
        this.maxDeltaWALCacheSize = config.walCacheSize();
        this.deltaWAL = deltaWAL;
        this.blockCache = blockCache;
        this.deltaWALCache = new LogCache(config.walCacheSize(), config.walUploadThreshold(), config.maxStreamNumPerStreamSetObject());
        DirectByteBufAlloc.registerOOMHandlers(new LogCacheEvictOOMHandler());
        this.streamManager = streamManager;
        this.objectManager = objectManager;
        this.s3Operator = s3Operator;

        this.drainBackoffTask = this.backgroundExecutor.scheduleWithFixedDelay(this::tryDrainBackoffRecords, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void startup() {
        try {
            LOGGER.info("S3Storage starting");
            recover();
            LOGGER.info("S3Storage start completed");
        } catch (Throwable e) {
            LOGGER.error("S3Storage start fail", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Upload WAL to S3 and close opening streams.
     */
    public void recover() throws Throwable {
        recover0(this.deltaWAL, this.streamManager, this.objectManager, LOGGER);
    }

    public void recover(WriteAheadLog deltaWAL, StreamManager streamManager, ObjectManager objectManager, Logger logger) throws Throwable {
        recover0(deltaWAL, streamManager, objectManager, logger);
    }

    void recover0(WriteAheadLog deltaWAL, StreamManager streamManager, ObjectManager objectManager, Logger logger) throws Throwable {
        deltaWAL.start();
        List<StreamMetadata> streams = streamManager.getOpeningStreams().get();

        LogCache.LogCacheBlock cacheBlock = recoverContinuousRecords(deltaWAL.recover(), streams, logger);
        Map<Long, Long> streamEndOffsets = new HashMap<>();
        cacheBlock.records().forEach((streamId, records) -> {
            if (!records.isEmpty()) {
                streamEndOffsets.put(streamId, records.get(records.size() - 1).getLastOffset());
            }
        });

        if (cacheBlock.size() != 0) {
            logger.info("try recover from crash, recover records bytes size {}", cacheBlock.size());
            DeltaWALUploadTask task = DeltaWALUploadTask.of(config, cacheBlock.records(), objectManager, s3Operator, uploadWALExecutor);
            task.prepare().thenCompose(nil -> task.upload()).thenCompose(nil -> task.commit()).get();
            cacheBlock.records().forEach((streamId, records) -> records.forEach(StreamRecordBatch::release));
        }
        deltaWAL.reset().get();
        for (StreamMetadata stream : streams) {
            long newEndOffset = streamEndOffsets.getOrDefault(stream.getStreamId(), stream.getEndOffset());
            logger.info("recover try close stream {} with new end offset {}", stream, newEndOffset);
        }
        CompletableFuture.allOf(
                streams
                        .stream()
                        .map(s -> streamManager.closeStream(s.getStreamId(), s.getEpoch()))
                        .toArray(CompletableFuture[]::new)
        ).get();
    }

    static LogCache.LogCacheBlock recoverContinuousRecords(Iterator<WriteAheadLog.RecoverResult> it, List<StreamMetadata> openingStreams) {
        return recoverContinuousRecords(it, openingStreams, LOGGER);
    }

    static LogCache.LogCacheBlock recoverContinuousRecords(Iterator<WriteAheadLog.RecoverResult> it, List<StreamMetadata> openingStreams, Logger logger) {
        Map<Long, Long> openingStreamEndOffsets = openingStreams.stream().collect(Collectors.toMap(StreamMetadata::getStreamId, StreamMetadata::getEndOffset));
        LogCache.LogCacheBlock cacheBlock = new LogCache.LogCacheBlock(1024L * 1024 * 1024);
        long logEndOffset = -1L;
        Map<Long, Long> streamNextOffsets = new HashMap<>();
        while (it.hasNext()) {
            WriteAheadLog.RecoverResult recoverResult = it.next();
            logEndOffset = recoverResult.recordOffset();
            ByteBuf recordBuf = recoverResult.record().duplicate();
            StreamRecordBatch streamRecordBatch = StreamRecordBatchCodec.decode(recordBuf);
            long streamId = streamRecordBatch.getStreamId();
            Long openingStreamEndOffset = openingStreamEndOffsets.get(streamId);
            if (openingStreamEndOffset == null) {
                // stream is already safe closed. so skip the stream records.
                recordBuf.release();
                continue;
            }
            if (streamRecordBatch.getBaseOffset() < openingStreamEndOffset) {
                // filter committed records.
                recordBuf.release();
                continue;
            }
            Long expectNextOffset = streamNextOffsets.get(streamId);
            if (expectNextOffset == null || expectNextOffset == streamRecordBatch.getBaseOffset()) {
                cacheBlock.put(streamRecordBatch);
                streamNextOffsets.put(streamRecordBatch.getStreamId(), streamRecordBatch.getLastOffset());
            } else {
                logger.error("unexpected WAL record, streamId={}, expectNextOffset={}, record={}", streamId, expectNextOffset, streamRecordBatch);
                streamRecordBatch.release();
            }
        }
        if (logEndOffset >= 0L) {
            cacheBlock.confirmOffset(logEndOffset);
        }
        cacheBlock.records().forEach((streamId, records) -> {
            if (!records.isEmpty()) {
                long startOffset = records.get(0).getBaseOffset();
                long expectedStartOffset = openingStreamEndOffsets.getOrDefault(streamId, startOffset);
                if (startOffset != expectedStartOffset) {
                    throw new IllegalStateException(String.format("[BUG] WAL data may lost, streamId %d endOffset=%d from controller, " +
                            "but WAL recovered records startOffset=%s", streamId, expectedStartOffset, startOffset));
                }
            }

        });

        return cacheBlock;
    }

    @Override
    public void shutdown() {
        drainBackoffTask.cancel(false);
        FutureUtil.suppress(drainBackoffTask::get, LOGGER);
        for (WalWriteRequest request : backoffRecords) {
            request.cf.completeExceptionally(new IOException("S3Storage is shutdown"));
        }
        deltaWAL.shutdownGracefully();
        backgroundExecutor.shutdown();
    }


    @Override
    public CompletableFuture<Void> append(StreamRecordBatch streamRecord) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<Void> cf = new CompletableFuture<>();
        // encoded before append to free heap ByteBuf.
        streamRecord.encoded();
        WalWriteRequest writeRequest = new WalWriteRequest(streamRecord, -1L, cf);
        handleAppendRequest(writeRequest);
        append0(writeRequest, false);
        cf.whenComplete((nil, ex) -> {
            streamRecord.release();
            OperationMetricsStats.getHistogram(S3Operation.APPEND_STORAGE).update(timerUtil.elapsedAs(TimeUnit.NANOSECONDS));
        });
        return cf;
    }

    /**
     * Append record to WAL.
     *
     * @return backoff status.
     */
    public boolean append0(WalWriteRequest request, boolean fromBackoff) {
        // TODO: storage status check, fast fail the request when storage closed.
        if (!fromBackoff && !backoffRecords.isEmpty()) {
            backoffRecords.offer(request);
            return true;
        }
        if (!tryAcquirePermit()) {
            if (!fromBackoff) {
                backoffRecords.offer(request);
            }
            OperationMetricsStats.getCounter(S3Operation.APPEND_STORAGE_LOG_CACHE_FULL).inc();
            if (System.currentTimeMillis() - lastLogTimestamp > 1000L) {
                LOGGER.warn("[BACKOFF] log cache size {} is larger than {}", deltaWALCache.size(), maxDeltaWALCacheSize);
                lastLogTimestamp = System.currentTimeMillis();
            }
            return true;
        }
        WriteAheadLog.AppendResult appendResult;
        try {
            StreamRecordBatch streamRecord = request.record;
            streamRecord.retain();
            appendResult = deltaWAL.append(streamRecord.encoded());
        } catch (WriteAheadLog.OverCapacityException e) {
            // the WAL write data align with block, 'WAL is full but LogCacheBlock is not full' may happen.
            forceUpload(LogCache.MATCH_ALL_STREAMS);
            if (!fromBackoff) {
                backoffRecords.offer(request);
            }
            if (System.currentTimeMillis() - lastLogTimestamp > 1000L) {
                LOGGER.warn("[BACKOFF] log over capacity", e);
                lastLogTimestamp = System.currentTimeMillis();
            }
            return true;
        }
        request.offset = appendResult.recordOffset();
        appendResult.future().thenAccept(nil -> handleAppendCallback(request));
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean tryAcquirePermit() {
        return deltaWALCache.size() < maxDeltaWALCacheSize;
    }

    private void tryDrainBackoffRecords() {
        try {
            for (; ; ) {
                WalWriteRequest request = backoffRecords.peek();
                if (request == null) {
                    break;
                }
                if (append0(request, true)) {
                    LOGGER.warn("try drain backoff record fail, still backoff");
                    break;
                }
                backoffRecords.poll();
            }
        } catch (Throwable e) {
            LOGGER.error("[UNEXPECTED] tryDrainBackoffRecords fail", e);
        }
    }

    @Override
    public CompletableFuture<ReadDataBlock> read(long streamId, long startOffset, long endOffset, int maxBytes) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<ReadDataBlock> cf = new CompletableFuture<>();
        FutureUtil.propagate(read0(streamId, startOffset, endOffset, maxBytes), cf);
        cf.whenComplete((nil, ex) -> OperationMetricsStats.getHistogram(S3Operation.READ_STORAGE).update(timerUtil.elapsedAs(TimeUnit.NANOSECONDS)));
        return cf;
    }

    private CompletableFuture<ReadDataBlock> read0(long streamId, long startOffset, long endOffset, int maxBytes) {
        List<StreamRecordBatch> logCacheRecords = deltaWALCache.get(streamId, startOffset, endOffset, maxBytes);
        if (!logCacheRecords.isEmpty() && logCacheRecords.get(0).getBaseOffset() <= startOffset) {
            return CompletableFuture.completedFuture(new ReadDataBlock(logCacheRecords, CacheAccessType.DELTA_WAL_CACHE_HIT));
        }
        if (!logCacheRecords.isEmpty()) {
            endOffset = logCacheRecords.get(0).getBaseOffset();
        }
        return blockCache.read(streamId, startOffset, endOffset, maxBytes).thenApplyAsync(readDataBlock -> {
            List<StreamRecordBatch> rst = new ArrayList<>(readDataBlock.getRecords());
            int remainingBytesSize = maxBytes - rst.stream().mapToInt(StreamRecordBatch::size).sum();
            int readIndex = -1;
            for (int i = 0; i < logCacheRecords.size() && remainingBytesSize > 0; i++) {
                readIndex = i;
                StreamRecordBatch record = logCacheRecords.get(i);
                rst.add(record);
                remainingBytesSize -= record.size();
            }
            if (readIndex < logCacheRecords.size()) {
                // release unnecessary record
                logCacheRecords.subList(readIndex + 1, logCacheRecords.size()).forEach(StreamRecordBatch::release);
            }
            continuousCheck(rst);
            return new ReadDataBlock(rst, readDataBlock.getCacheAccessType());
        }).whenComplete((rst, ex) -> {
            if (ex != null) {
                logCacheRecords.forEach(StreamRecordBatch::release);
            }
        });
    }

    private void continuousCheck(List<StreamRecordBatch> records) {
        long expectStartOffset = -1L;
        for (StreamRecordBatch record : records) {
            if (expectStartOffset == -1L || record.getBaseOffset() == expectStartOffset) {
                expectStartOffset = record.getLastOffset();
            } else {
                throw new IllegalArgumentException("Continuous check fail" + records);
            }
        }
    }

    /**
     * Force upload stream WAL cache to S3. Use group upload to avoid generate too many S3 objects when broker shutdown.
     */
    @Override
    public CompletableFuture<Void> forceUpload(long streamId) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        List<CompletableFuture<Void>> inflightWALUploadTasks = new ArrayList<>(this.inflightWALUploadTasks);
        // await inflight stream set object upload tasks to group force upload tasks.
        CompletableFuture.allOf(inflightWALUploadTasks.toArray(new CompletableFuture[0])).whenCompleteAsync((nil, ex) -> {
            uploadDeltaWAL(streamId);
            FutureUtil.propagate(CompletableFuture.allOf(this.inflightWALUploadTasks.toArray(new CompletableFuture[0])), cf);
            if (LogCache.MATCH_ALL_STREAMS != streamId) {
                callbackSequencer.tryFree(streamId);
            }
        });
        return cf;
    }

    private void handleAppendRequest(WalWriteRequest request) {
        callbackSequencer.before(request);
    }

    private void handleAppendCallback(WalWriteRequest request) {
        TimerUtil timer = new TimerUtil();
        List<WalWriteRequest> waitingAckRequests;
        Lock lock = getStreamCallbackLock(request.record.getStreamId());
        lock.lock();
        try {
            waitingAckRequests = callbackSequencer.after(request);
            waitingAckRequests.forEach(r -> r.record.retain());
            for (WalWriteRequest waitingAckRequest : waitingAckRequests) {
                if (deltaWALCache.put(waitingAckRequest.record)) {
                    // cache block is full, trigger WAL upload.
                    uploadDeltaWAL();
                }
            }
        } finally {
            lock.unlock();
        }
        for (WalWriteRequest waitingAckRequest : waitingAckRequests) {
            waitingAckRequest.cf.complete(null);
        }
        OperationMetricsStats.getHistogram(S3Operation.APPEND_STORAGE_APPEND_CALLBACK).update(timer.elapsedAs(TimeUnit.NANOSECONDS));
    }

    private Lock getStreamCallbackLock(long streamId) {
        return streamCallbackLocks[(int) ((streamId & Long.MAX_VALUE) % NUM_STREAM_CALLBACK_LOCKS)];
    }

    @SuppressWarnings("UnusedReturnValue")
    CompletableFuture<Void> uploadDeltaWAL() {
        return uploadDeltaWAL(LogCache.MATCH_ALL_STREAMS);
    }

    CompletableFuture<Void> uploadDeltaWAL(long streamId) {
        synchronized (deltaWALCache) {
            deltaWALCache.setConfirmOffset(callbackSequencer.getWALConfirmOffset());
            Optional<LogCache.LogCacheBlock> blockOpt = deltaWALCache.archiveCurrentBlockIfContains(streamId);
            if (blockOpt.isPresent()) {
                LogCache.LogCacheBlock logCacheBlock = blockOpt.get();
                DeltaWALUploadTaskContext context = new DeltaWALUploadTaskContext(logCacheBlock);
                context.objectManager = this.objectManager;
                return uploadDeltaWAL(context);
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    // only for test
    CompletableFuture<Void> uploadDeltaWAL(LogCache.LogCacheBlock logCacheBlock) {
        DeltaWALUploadTaskContext context = new DeltaWALUploadTaskContext(logCacheBlock);
        context.objectManager = this.objectManager;
        return uploadDeltaWAL(context);
    }

    /**
     * Upload cache block to S3. The earlier cache block will have smaller objectId and commit first.
     */
    CompletableFuture<Void> uploadDeltaWAL(DeltaWALUploadTaskContext context) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<Void> cf = new CompletableFuture<>();
        context.cf = cf;
        inflightWALUploadTasks.add(cf);
        backgroundExecutor.execute(() -> FutureUtil.exec(() -> uploadDeltaWAL0(context), cf, LOGGER, "uploadDeltaWAL"));
        cf.whenComplete((nil, ex) -> {
            OperationMetricsStats.getHistogram(S3Operation.UPLOAD_STORAGE_WAL).update(timerUtil.elapsedAs(TimeUnit.NANOSECONDS));
            inflightWALUploadTasks.remove(cf);
            if (ex != null) {
                LOGGER.error("upload delta WAL fail", ex);
            }
        });
        return cf;
    }

    private void uploadDeltaWAL0(DeltaWALUploadTaskContext context) {
        context.task = DeltaWALUploadTask.of(config, context.cache.records(), context.objectManager, s3Operator, uploadWALExecutor);
        boolean walObjectPrepareQueueEmpty = walPrepareQueue.isEmpty();
        walPrepareQueue.add(context);
        if (!walObjectPrepareQueueEmpty) {
            // there is another WAL upload task is preparing, just return.
            return;
        }
        prepareDeltaWALUpload(context);
    }

    private void prepareDeltaWALUpload(DeltaWALUploadTaskContext context) {
        context.task.prepare().thenAcceptAsync(nil -> {
            // 1. poll out current task and trigger upload.
            DeltaWALUploadTaskContext peek = walPrepareQueue.poll();
            Objects.requireNonNull(peek).task.upload();
            // 2. add task to commit queue.
            boolean walObjectCommitQueueEmpty = walCommitQueue.isEmpty();
            walCommitQueue.add(peek);
            if (walObjectCommitQueueEmpty) {
                commitDeltaWALUpload(peek);
            }
            // 3. trigger next task to prepare.
            DeltaWALUploadTaskContext next = walPrepareQueue.peek();
            if (next != null) {
                prepareDeltaWALUpload(next);
            }
        }, backgroundExecutor);
    }

    private void commitDeltaWALUpload(DeltaWALUploadTaskContext context) {
        context.task.commit().thenAcceptAsync(nil -> {
            // 1. poll out current task
            walCommitQueue.poll();
            if (context.cache.confirmOffset() != 0) {
                LOGGER.info("try trim WAL to {}", context.cache.confirmOffset());
                deltaWAL.trim(context.cache.confirmOffset());
            }
            // transfer records ownership to block cache.
            freeCache(context.cache);
            context.cf.complete(null);

            // 2. trigger next task to commit.
            DeltaWALUploadTaskContext next = walCommitQueue.peek();
            if (next != null) {
                commitDeltaWALUpload(next);
            }
        }, backgroundExecutor).exceptionally(ex -> {
            LOGGER.error("Unexpected exception when commit stream set object", ex);
            context.cf.completeExceptionally(ex);
            System.err.println("Unexpected exception when commit stream set object");
            //noinspection CallToPrintStackTrace
            ex.printStackTrace();
            System.exit(1);
            return null;
        });
    }

    private void freeCache(LogCache.LogCacheBlock cacheBlock) {
        deltaWALCache.markFree(cacheBlock);
    }

    /**
     * WALCallbackSequencer is used to sequence the unordered returned persistent data.
     */
    static class WALCallbackSequencer {
        public static final long NOOP_OFFSET = -1L;
        private final Map<Long, StreamRequestQueue> stream2requests = new ConcurrentHashMap<>();
        /**
         * The offset before and including which all requests have been confirmed.
         * Note: it is updated by {@link #updateWALConfirmOffset}, and it is not real-time.
         */
        private final AtomicLong walConfirmOffset = new AtomicLong(NOOP_OFFSET);

        /**
         * Add request to stream sequence queue.
         * When the {@code request.record.getStreamId()} is different, concurrent calls are allowed.
         * When the {@code request.record.getStreamId()} is the same, concurrent calls are not allowed. And it is
         * necessary to ensure that calls are made in the order of increasing offsets.
         */
        public void before(WalWriteRequest request) {
            try {
                StreamRequestQueue queue = stream2requests.computeIfAbsent(request.record.getStreamId(),
                        s -> new StreamRequestQueue(walConfirmOffset.get()));
                queue.add(request);
            } catch (Throwable ex) {
                request.cf.completeExceptionally(ex);
            }
        }

        /**
         * Try pop sequence persisted request from stream queue and move forward wal inclusive confirm offset.
         * When the {@code request.record.getStreamId()} is different, concurrent calls are allowed.
         * When the {@code request.record.getStreamId()} is the same, concurrent calls are not allowed.
         *
         * @return popped sequence persisted request.
         */
        public List<WalWriteRequest> after(WalWriteRequest request) {
            request.persisted = true;
            long streamId = request.record.getStreamId();
            StreamRequestQueue queue = stream2requests.get(streamId);
            return queue.popSequentialRequests(request.offset);
        }

        /**
         * Get WAL inclusive confirm offset.
         *
         * @return inclusive confirm offset.
         */
        public long getWALConfirmOffset() {
            updateWALConfirmOffset();
            return walConfirmOffset.get();
        }

        /**
         * Try free stream related resources.
         */
        public void tryFree(long streamId) {
            StreamRequestQueue queue = stream2requests.get(streamId);
            if (queue != null && queue.isEmpty()) {
                stream2requests.remove(streamId, queue);
            }
        }

        /**
         * Update WAL inclusive confirm offset.
         * It is thread safe.
         */
        private void updateWALConfirmOffset() {
            stream2requests.values().stream()
                    .mapToLong(StreamRequestQueue::getConfirmOffset)
                    .min()
                    .ifPresent(walConfirmOffset::set);
        }

        /**
         * StreamRequestQueue contains a queue of requests for a stream.
         * Every request in the queue has the same streamId and is ordered by offset.
         */
        static class StreamRequestQueue {
            private final BlockingQueue<WalWriteRequest> queue = new LinkedBlockingQueue<>();
            private long confirmOffset;

            public StreamRequestQueue(long confirmOffset) {
                this.confirmOffset = confirmOffset;
            }

            /**
             * Add a request to the queue.
             */
            public void add(WalWriteRequest request) {
                queue.add(request);
            }

            /**
             * Try to pop sequential persisted requests from the queue.
             * It returns an empty list if the first request in the queue's offset is not equal to the given offset.
             * Note: this method can be called with {@link #add} concurrently but NOT with itself.
             */
            public List<WalWriteRequest> popSequentialRequests(long offset) {
                List<WalWriteRequest> rst = new LinkedList<>();
                WalWriteRequest peek = queue.peek();
                if (peek == null || peek.offset != offset) {
                    return Collections.emptyList();
                }
                assert peek.persisted;
                if (queue.remove(peek)) {
                    rst.add(peek);
                } else {
                    // Should not happen.
                    assert false;
                }

                // Try to pop following sequential persisted requests.
                for (; ; ) {
                    peek = queue.peek();
                    if (peek == null || !peek.persisted) {
                        break;
                    }
                    assert peek.offset > offset;
                    offset = peek.offset;
                    if (queue.remove(peek)) {
                        rst.add(peek);
                    } else {
                        // Should not happen.
                        assert false;
                    }
                }
                confirmOffset = offset;
                return rst;
            }

            /**
             * It returns {@link #NOOP_OFFSET} if no request has been confirmed.
             */
            public long getConfirmOffset() {
                return confirmOffset;
            }

            public boolean isEmpty() {
                return queue.isEmpty();
            }
        }
    }

    class LogCacheEvictOOMHandler implements DirectByteBufAlloc.OOMHandler {
        @Override
        public int handle(int memoryRequired) {
            try {
                CompletableFuture<Integer> cf = new CompletableFuture<>();
                FutureUtil.exec(() -> cf.complete(deltaWALCache.forceFree(memoryRequired)), cf, LOGGER, "handleOOM");
                return cf.get();
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    public static class DeltaWALUploadTaskContext {
        LogCache.LogCacheBlock cache;
        DeltaWALUploadTask task;
        CompletableFuture<Void> cf;
        ObjectManager objectManager;

        public DeltaWALUploadTaskContext(LogCache.LogCacheBlock cache) {
            this.cache = cache;
        }
    }
}
