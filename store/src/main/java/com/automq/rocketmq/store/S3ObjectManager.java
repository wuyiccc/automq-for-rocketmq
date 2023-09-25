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

import apache.rocketmq.controller.v1.S3StreamObject;
import apache.rocketmq.controller.v1.S3WALObject;
import apache.rocketmq.controller.v1.SubStream;
import com.automq.rocketmq.common.util.Pair;
import com.automq.rocketmq.metadata.StoreMetadataService;
import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.metadata.S3ObjectType;
import com.automq.stream.s3.metadata.S3StreamConstant;
import com.automq.stream.s3.metadata.StreamOffsetRange;
import com.automq.stream.s3.objects.CommitStreamObjectRequest;
import com.automq.stream.s3.objects.CommitWALObjectRequest;
import com.automq.stream.s3.objects.CommitWALObjectResponse;
import com.automq.stream.s3.objects.ObjectManager;
import com.automq.stream.s3.objects.ObjectStreamRange;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3ObjectManager implements ObjectManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3ObjectManager.class);
    private final StoreMetadataService metaService;

    public S3ObjectManager(StoreMetadataService metaService) {
        this.metaService = metaService;
    }

    @Override
    public CompletableFuture<Long> prepareObject(int count, long ttl) {
        // Covert ttl from milliseconds to minutes
        int minutes = (int) TimeUnit.MILLISECONDS.toMinutes(ttl);
        return metaService.prepareS3Objects(count, minutes);
    }

    @Override
    public CompletableFuture<CommitWALObjectResponse> commitWALObject(CommitWALObjectRequest request) {
        // Build S3WALObject
        S3WALObject.Builder builder = S3WALObject.newBuilder();
        builder.setObjectId(request.getObjectId());
        builder.setSequenceId(request.getOrderId());
        builder.setObjectSize(request.getObjectSize());

        // Collect SubStream from request
        Map<Long, SubStream> subStreams = request
            .getStreamRanges()
            .stream()
            .collect(Collectors.toMap(ObjectStreamRange::getStreamId,
                range -> {
                    SubStream.Builder subStreamBuilder = SubStream.newBuilder();
                    subStreamBuilder.setStreamId(range.getStreamId());
                    subStreamBuilder.setStartOffset(range.getStartOffset());
                    subStreamBuilder.setEndOffset(range.getEndOffset());
                    return subStreamBuilder.build();
                }));

        builder.putAllSubStreams(subStreams);
        S3WALObject walObject = builder.build();

        // Build stream objects
        List<S3StreamObject> streamObjects = request.getStreamObjects().stream().map(streamObject -> {
            S3StreamObject.Builder streamObjectBuilder = S3StreamObject.newBuilder();
            streamObjectBuilder.setStreamId(streamObject.getStreamId());
            streamObjectBuilder.setStartOffset(streamObject.getStartOffset());
            streamObjectBuilder.setEndOffset(streamObject.getEndOffset());
            streamObjectBuilder.setObjectId(streamObject.getObjectId());
            streamObjectBuilder.setObjectSize(streamObject.getObjectSize());
            return streamObjectBuilder.build();
        }).toList();

        LOGGER.info("Commit WAL object: {}, along with split stream objects: {}, compacted objects: {}",
            walObject, streamObjects, request.getCompactedObjectIds());

        // Build compacted objects
        return metaService.commitWalObject(walObject, streamObjects, request.getCompactedObjectIds()).thenApply(resp -> new CommitWALObjectResponse());
    }

    @Override
    public CompletableFuture<Void> commitStreamObject(CommitStreamObjectRequest request) {
        // Build S3StreamObject
        S3StreamObject.Builder builder = S3StreamObject.newBuilder();
        builder.setStreamId(request.getStreamId());
        builder.setStartOffset(request.getStartOffset());
        builder.setEndOffset(request.getEndOffset());
        builder.setObjectId(request.getObjectId());
        builder.setObjectSize(request.getObjectSize());
        S3StreamObject object = builder.build();

        LOGGER.info("Commit stream object: {}, compacted objects: {}", object, request.getSourceObjectIds());

        return metaService.commitStreamObject(object, request.getSourceObjectIds());
    }

    static class S3ObjectMetadataWrapper {
        private final S3ObjectMetadata metadata;
        private final long startOffset;
        private final long endOffset;

        public S3ObjectMetadataWrapper(S3ObjectMetadata metadata, long startOffset, long endOffset) {
            this.metadata = metadata;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public S3ObjectMetadata getMetadata() {
            return metadata;
        }

        public long getStartOffset() {
            return startOffset;
        }

        public long getEndOffset() {
            return endOffset;
        }
    }

    @Override
    public CompletableFuture<List<S3ObjectMetadata>> getObjects(long streamId, long startOffset, long endOffset,
        int limit) {
        CompletableFuture<Pair<List<S3StreamObject>, List<S3WALObject>>> cf = metaService.listObjects(streamId, startOffset, endOffset, limit);
        // Retrieve S3ObjectMetadata from stream objects and wal objects
        return cf.thenApply(pair -> {
            // Sort the streamObjects in ascending order of startOffset
            pair.left().sort((o1, o2) -> (int) (o1.getStartOffset() - o2.getStartOffset()));
            Queue<S3ObjectMetadataWrapper> streamObjects = pair.left().stream().map(obj -> {
                long start = obj.getStartOffset();
                long end = obj.getEndOffset();
                return new S3ObjectMetadataWrapper(convertFrom(obj), start, end);
            }).collect(Collectors.toCollection(LinkedList::new));
            // Sort the walObjects in ascending order of startOffset
            pair.right().sort((o1, o2) -> (int) (o1.getSubStreamsMap().get(streamId).getStartOffset() - o2.getSubStreamsMap().get(streamId).getStartOffset()));
            Queue<S3ObjectMetadataWrapper> walObjects = pair.right().stream().map(obj -> {
                long start = obj.getSubStreamsMap().get(streamId).getStartOffset();
                long end = obj.getSubStreamsMap().get(streamId).getEndOffset();
                return new S3ObjectMetadataWrapper(convertFrom(obj), start, end);
            }).collect(Collectors.toCollection(LinkedList::new));
            // Merge sort the two object lists
            List<S3ObjectMetadata> objectMetadataList = new ArrayList<>();
            long nextStartOffset = startOffset;
            int need = limit;
            while (need > 0
                && nextStartOffset < endOffset
                && (!streamObjects.isEmpty() || !walObjects.isEmpty())) {
                S3ObjectMetadataWrapper cur = null;
                if (walObjects.isEmpty() || !streamObjects.isEmpty() && streamObjects.peek().getStartOffset() <= walObjects.peek().getStartOffset()) {
                    // Stream object has higher priority
                    cur = streamObjects.poll();
                } else {
                    cur = walObjects.poll();
                }
                // Skip the object if it is not in the range
                if (cur.getEndOffset() <= nextStartOffset) {
                    continue;
                }
                if (cur.getStartOffset() > nextStartOffset) {
                    // No object can be added, break the loop
                    // Consider we need [100, 300), but the first object is [200, 300), then we need to break the loop
                    LOGGER.error("Found a hole in the returned S3Object list, streamId: {}, startOffset: {}, streamObjects: {}, walObjects: {}",
                        streamId, startOffset, streamObjects, walObjects);
                    // Throw exception to trigger the retry
                    throw new RuntimeException("Found a hole in the returned S3Object list for current getObjects request");
                }
                objectMetadataList.add(cur.getMetadata());
                nextStartOffset = cur.getEndOffset();
                need--;
            }
            LOGGER.trace("Get objects from streamId: {}, startOffset: {}, endOffset: {}, limit: {}, result: {}",
                streamId, startOffset, endOffset, limit, objectMetadataList);
            return objectMetadataList;
        });
    }

    @Override
    public CompletableFuture<List<S3ObjectMetadata>> getServerObjects() {
        CompletableFuture<List<S3WALObject>> walObjects = metaService.listWALObjects();
        // Covert to the list of S3ObjectMetadata
        return walObjects.thenApply(objects -> objects.stream().map(this::convertFrom).toList());
    }

    @Override
    public CompletableFuture<List<S3ObjectMetadata>> getStreamObjects(long streamId, long startOffset, long endOffset,
        int limit) {
        CompletableFuture<List<S3StreamObject>> streamObjects = metaService.listStreamObjects(streamId, startOffset, endOffset, limit);
        return streamObjects.thenApply(objects -> objects.stream().map(this::convertFrom).toList());
    }

    private S3ObjectMetadata convertFrom(S3StreamObject object) {
        StreamOffsetRange offsetRange = new StreamOffsetRange(object.getStreamId(), object.getStartOffset(), object.getEndOffset());
        return new S3ObjectMetadata(object.getObjectId(), S3ObjectType.STREAM, List.of(offsetRange), object.getBaseDataTimestamp(),
            object.getCommittedTimestamp(), object.getObjectSize(), S3StreamConstant.INVALID_ORDER_ID);
    }

    private S3ObjectMetadata convertFrom(S3WALObject object) {
        List<StreamOffsetRange> offsetRanges = object.getSubStreamsMap().values()
            .stream()
            .map(subStream -> new StreamOffsetRange(subStream.getStreamId(), subStream.getStartOffset(), subStream.getEndOffset())).toList();
        return new S3ObjectMetadata(object.getObjectId(), S3ObjectType.WAL, offsetRanges, object.getBaseDataTimestamp(),
            object.getCommittedTimestamp(), object.getObjectSize(), object.getSequenceId());
    }
}