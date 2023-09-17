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

package com.automq.rocketmq.store.mock;

import com.automq.rocketmq.metadata.StoreMetadataService;
import java.nio.ByteBuffer;

public class MockStoreMetadataService implements StoreMetadataService {
    @Override
    public long getStreamId(long topicId, long queueId) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(0, (int) topicId);
        buffer.putInt(4, (int) queueId);
        return buffer.getLong(0);
    }

    @Override
    public long getOperationLogStreamId(long topicId, long queueId) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(0, (int) topicId);
        buffer.putInt(4, (int) queueId);
        return buffer.getLong(0);
    }

    @Override
    public long getRetryStreamId(long consumerGroupId, long topicId, long queueId) {
        return 0;
    }

    @Override
    public long getDeadLetterStreamId(long consumerGroupId, long topicId, long queueId) {
        return 0;
    }

    @Override
    public void advanceConsumeOffset(long consumerGroupId, long topicId, long queueId, long offset) {

    }
}