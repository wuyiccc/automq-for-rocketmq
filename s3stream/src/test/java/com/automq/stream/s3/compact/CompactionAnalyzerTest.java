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

package com.automq.stream.s3.compact;

import com.automq.stream.s3.compact.objects.CompactedObject;
import com.automq.stream.s3.compact.objects.CompactedObjectBuilder;
import com.automq.stream.s3.compact.objects.CompactionType;
import com.automq.stream.s3.compact.objects.StreamDataBlock;
import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.metadata.S3ObjectType;
import com.automq.stream.s3.metadata.StreamOffsetRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
@Tag("S3Unit")
public class CompactionAnalyzerTest extends CompactionTestBase {

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
    }

    @AfterEach
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testReadObjectIndices() {
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        Map<Long, List<StreamDataBlock>> expectedBlocksMap = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                OBJECT_2, List.of(
                        new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        assertTrue(compare(streamDataBlocksMap, expectedBlocksMap));
    }

    @Test
    public void testReadObjectIndicesWithTrimmedData() {
        List<S3ObjectMetadata> objectMetadataList = new ArrayList<>();
        for (int i = 0; i < S3_WAL_OBJECT_METADATA_LIST.size(); i++) {
            S3ObjectMetadata s3ObjectMetadata = S3_WAL_OBJECT_METADATA_LIST.get(i);
            if (i == 0) {
                s3ObjectMetadata = new S3ObjectMetadata(s3ObjectMetadata.objectId(), s3ObjectMetadata.getType()
                        , new ArrayList<>(s3ObjectMetadata.getOffsetRanges().subList(1, s3ObjectMetadata.getOffsetRanges().size())),
                        s3ObjectMetadata.dataTimeInMs(), s3ObjectMetadata.committedTimestamp(), s3ObjectMetadata.objectSize(), s3ObjectMetadata.getOrderId());
            }
            objectMetadataList.add(s3ObjectMetadata);
        }
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(objectMetadataList, s3Operator);
        Map<Long, List<StreamDataBlock>> expectedBlocksMap = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                OBJECT_2, List.of(
                        new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        assertTrue(compare(streamDataBlocksMap, expectedBlocksMap));
    }

    @Test
    public void testFilterBlocksToCompact() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, STREAM_SPLIT_SIZE);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        Map<Long, List<StreamDataBlock>> filteredMap = compactionAnalyzer.filterBlocksToCompact(streamDataBlocksMap);
        assertTrue(compare(filteredMap, streamDataBlocksMap));
    }

    @Test
    public void testFilterBlocksToCompact2() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, STREAM_SPLIT_SIZE);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_0, 0, 20, 0, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 1, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 20, 25, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                OBJECT_2, List.of(
                        new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)),
                OBJECT_3, List.of(
                        new StreamDataBlock(STREAM_3, 0, 50, 1, OBJECT_3, -1, -1, 1)));
        Map<Long, List<StreamDataBlock>> result = compactionAnalyzer.filterBlocksToCompact(streamDataBlocksMap);
        Map<Long, List<StreamDataBlock>> expectedBlocksMap = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_0, 0, 20, 0, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 1, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 20, 25, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)));
        assertTrue(compare(result, expectedBlocksMap));
    }

    @Test
    public void testSortStreamRangePositions() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, STREAM_SPLIT_SIZE);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        List<StreamDataBlock> sortedStreamDataBlocks = compactionAnalyzer.sortStreamRangePositions(streamDataBlocksMap);
        List<StreamDataBlock> expectedBlocks = List.of(
                new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1),
                new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1),
                new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1),
                new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1),
                new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1),
                new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1),
                new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1),
                new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1));
        for (int i = 0; i < sortedStreamDataBlocks.size(); i++) {
            assertTrue(compare(sortedStreamDataBlocks.get(i), expectedBlocks.get(i)));
        }
    }

    @Test
    public void testBuildCompactedObject1() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, 100);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        List<CompactedObjectBuilder> compactedObjectBuilders = compactionAnalyzer.buildCompactedObjects(streamDataBlocksMap);
        List<CompactedObjectBuilder> expectedCompactedObject = List.of(
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        for (int i = 0; i < compactedObjectBuilders.size(); i++) {
            assertTrue(compare(compactedObjectBuilders.get(i), expectedCompactedObject.get(i)));
        }
    }

    @Test
    public void testBuildCompactedObject2() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, 30);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        List<CompactedObjectBuilder> compactedObjectBuilders = compactionAnalyzer.buildCompactedObjects(streamDataBlocksMap);
        List<CompactedObjectBuilder> expectedCompactedObject = List.of(
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        for (int i = 0; i < compactedObjectBuilders.size(); i++) {
            assertTrue(compare(compactedObjectBuilders.get(i), expectedCompactedObject.get(i)));
        }
    }

    @Test
    public void testCompactionPlans1() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(CACHE_SIZE, EXECUTION_SCORE_THRESHOLD, 100);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        List<CompactionPlan> compactionPlans = compactionAnalyzer.analyze(streamDataBlocksMap);
        Assertions.assertEquals(1, compactionPlans.size());
        List<CompactedObject> expectCompactedObjects = List.of(
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1))
                        .build());
        Map<Long, List<StreamDataBlock>> expectObjectStreamDataBlocks = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)),
                OBJECT_2, List.of(
                        new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        CompactionPlan compactionPlan = compactionPlans.get(0);
        for (int i = 0; i < compactionPlan.compactedObjects().size(); i++) {
            assertTrue(compare(compactionPlan.compactedObjects().get(i), expectCompactedObjects.get(i)));
        }
        for (Long objectId : compactionPlan.streamDataBlocksMap().keySet()) {
            assertTrue(compare(compactionPlan.streamDataBlocksMap().get(objectId), expectObjectStreamDataBlocks.get(objectId)));
        }
    }

    private void checkCompactionPlan2(List<CompactionPlan> compactionPlans) {
        Assertions.assertEquals(2, compactionPlans.size());

        // first iteration
        List<CompactedObject> expectCompactedObjects = List.of(
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1))
                        .addStreamDataBlock(new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1))
                        .build());
        Map<Long, List<StreamDataBlock>> expectObjectStreamDataBlocks = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_0, 0, 15, 0, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 25, 30, 1, OBJECT_0, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 30, 60, 2, OBJECT_0, -1, -1, 1)),
                OBJECT_1, List.of(
                        new StreamDataBlock(STREAM_0, 15, 20, 0, OBJECT_1, -1, -1, 1),
                        new StreamDataBlock(STREAM_1, 60, 120, 1, OBJECT_1, -1, -1, 1)));
        CompactionPlan compactionPlan = compactionPlans.get(0);
        for (int i = 0; i < compactionPlan.compactedObjects().size(); i++) {
            assertTrue(compare(compactionPlan.compactedObjects().get(i), expectCompactedObjects.get(i)));
        }
        for (Long objectId : compactionPlan.streamDataBlocksMap().keySet()) {
            assertTrue(compare(compactionPlan.streamDataBlocksMap().get(objectId), expectObjectStreamDataBlocks.get(objectId)));
        }

        // second iteration
        expectCompactedObjects = List.of(
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.SPLIT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1))
                        .build(),
                new CompactedObjectBuilder()
                        .setType(CompactionType.COMPACT)
                        .addStreamDataBlock(new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1))
                        .build());
        expectObjectStreamDataBlocks = Map.of(
                OBJECT_0, List.of(
                        new StreamDataBlock(STREAM_2, 30, 60, 3, OBJECT_0, -1, -1, 1)),
                OBJECT_2, List.of(
                        new StreamDataBlock(STREAM_1, 400, 500, 0, OBJECT_2, -1, -1, 1),
                        new StreamDataBlock(STREAM_2, 230, 270, 1, OBJECT_2, -1, -1, 1)));
        compactionPlan = compactionPlans.get(1);
        for (int i = 0; i < compactionPlan.compactedObjects().size(); i++) {
            assertTrue(compare(compactionPlan.compactedObjects().get(i), expectCompactedObjects.get(i)));
        }
        for (Long objectId : compactionPlan.streamDataBlocksMap().keySet()) {
            assertTrue(compare(compactionPlan.streamDataBlocksMap().get(objectId), expectObjectStreamDataBlocks.get(objectId)));
        }
    }

    @Test
    public void testCompactionPlans2() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(300, EXECUTION_SCORE_THRESHOLD, 100);
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(S3_WAL_OBJECT_METADATA_LIST, s3Operator);
        List<CompactionPlan> compactionPlans = compactionAnalyzer.analyze(streamDataBlocksMap);
        checkCompactionPlan2(compactionPlans);
    }

    @Test
    public void testCompactionPlansWithInvalidObject() {
        CompactionAnalyzer compactionAnalyzer = new CompactionAnalyzer(300, EXECUTION_SCORE_THRESHOLD, 100);
        List<S3ObjectMetadata> s3ObjectMetadata = new ArrayList<>(S3_WAL_OBJECT_METADATA_LIST);
        s3ObjectMetadata.add(
                new S3ObjectMetadata(100, S3ObjectType.WAL,
                        List.of(new StreamOffsetRange(STREAM_2, 1000, 1200)), System.currentTimeMillis(),
                        System.currentTimeMillis(), 512, 100));
        Map<Long, List<StreamDataBlock>> streamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(s3ObjectMetadata, s3Operator);
        List<CompactionPlan> compactionPlans = compactionAnalyzer.analyze(streamDataBlocksMap);
        checkCompactionPlan2(compactionPlans);
    }
}