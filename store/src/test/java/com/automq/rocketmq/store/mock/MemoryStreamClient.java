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

package com.automq.rocketmq.store.mock;

import com.automq.stream.api.AppendResult;
import com.automq.stream.api.CreateStreamOptions;
import com.automq.stream.api.FetchResult;
import com.automq.stream.api.OpenStreamOptions;
import com.automq.stream.api.ReadOptions;
import com.automq.stream.api.RecordBatch;
import com.automq.stream.api.RecordBatchWithContext;
import com.automq.stream.api.Stream;
import com.automq.stream.api.StreamClient;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A memory implementation of {@link StreamClient}.
 * <p>
 * This implementation is only used for test.
 */
public class MemoryStreamClient implements StreamClient {
    private final AtomicLong streamIdAlloc = new AtomicLong();
    private final ConcurrentHashMap<Long, Stream> streamMap = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Stream> createAndOpenStream(CreateStreamOptions options) {
        long id = streamIdAlloc.getAndIncrement();
        return openStream(id, null);
    }

    @Override
    public CompletableFuture<Stream> openStream(long streamId, OpenStreamOptions options) {
        Stream stream = streamMap.computeIfAbsent(streamId, MemoryStream::new);
        return CompletableFuture.completedFuture(stream);
    }

    @Override
    public Optional<Stream> getStream(long streamId) {
        return Optional.ofNullable(streamMap.get(streamId));
    }

    @Override
    public void shutdown() {

    }

    static class MemoryStream implements Stream {
        private final AtomicLong nextOffsetAlloc = new AtomicLong();
        private final AtomicLong startOffset = new AtomicLong();
        private NavigableMap<Long, RecordBatchWithContext> recordMap = new ConcurrentSkipListMap<>();
        private final long streamId;

        public MemoryStream(long id) {
            streamId = id;
        }

        @Override
        public long streamId() {
            return streamId;
        }

        @Override
        public long startOffset() {
            return startOffset.get();
        }

        @Override
        public long confirmOffset() {
            return nextOffsetAlloc.get();
        }

        @Override
        public long nextOffset() {
            return nextOffsetAlloc.get();
        }

        @Override
        public CompletableFuture<AppendResult> append(RecordBatch recordBatch) {
            long baseOffset = nextOffsetAlloc.getAndAdd(recordBatch.count());
            recordMap.put(baseOffset, new RecordBatchWithContextWrapper(recordBatch, baseOffset));
            return CompletableFuture.completedFuture(() -> baseOffset);
        }

        @Override
        public CompletableFuture<FetchResult> fetch(long startOffset, long endOffset, int maxBytesHint, ReadOptions readOptions) {
            List<RecordBatchWithContext> records = new ArrayList<>(recordMap.subMap(startOffset, endOffset).values());
            return CompletableFuture.completedFuture(() -> records);
        }

        @Override
        public CompletableFuture<Void> trim(long newStartOffset) {
            recordMap = new ConcurrentSkipListMap<>(recordMap.tailMap(newStartOffset));
            if (newStartOffset > startOffset.get()) {
                startOffset.set(newStartOffset);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> destroy() {
            recordMap.clear();
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class RecordBatchWithContextWrapper implements RecordBatchWithContext {
        private final RecordBatch recordBatch;
        private final long baseOffset;

        public RecordBatchWithContextWrapper(RecordBatch recordBatch, long baseOffset) {
            this.recordBatch = recordBatch;
            this.baseOffset = baseOffset;
        }

        @Override
        public long baseOffset() {
            return baseOffset;
        }

        @Override
        public long lastOffset() {
            return baseOffset + recordBatch.count() - 1;
        }

        @Override
        public int count() {
            return recordBatch.count();
        }

        @Override
        public long baseTimestamp() {
            return recordBatch.baseTimestamp();
        }

        @Override
        public Map<String, String> properties() {
            return recordBatch.properties();
        }

        @Override
        public ByteBuffer rawPayload() {
            return recordBatch.rawPayload().duplicate();
        }
    }
}
