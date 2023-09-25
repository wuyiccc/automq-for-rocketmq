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

package com.automq.rocketmq.controller.metadata;

import apache.rocketmq.controller.v1.Code;
import com.automq.rocketmq.controller.ControllerServiceImpl;
import com.automq.rocketmq.controller.ControllerTestServer;
import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.metadata.database.dao.Node;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class GrpcControllerClientTest {

    @Test
    public void testRegisterBroker() throws IOException, ControllerException, ExecutionException, InterruptedException {
        String name = "broker-name";
        String address = "localhost:1234";
        String instanceId = "i-ctrl";
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Node node = new Node();
        node.setId(1);
        node.setEpoch(1);
        Mockito.when(metadataStore.registerBrokerNode(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString())).thenReturn(node);
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            Node result = client.registerBroker(String.format("localhost:%d", port), name, address, instanceId).get();
            Assertions.assertEquals(1, result.getId());
            Assertions.assertEquals(1, result.getEpoch());
            Assertions.assertEquals(name, result.getName());
            Assertions.assertEquals(address, result.getAddress());
            Assertions.assertEquals(instanceId, result.getInstanceId());
        }
    }

    @Test
    public void testRegisterBroker_badTarget() throws IOException, ControllerException {
        String name = "broker-name";
        String address = "localhost:1234";
        String instanceId = "i-ctrl";
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Node node = new Node();
        node.setId(1);
        node.setEpoch(1);
        Mockito.when(metadataStore.registerBrokerNode(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString())).thenReturn(node);
        ControllerClient client = new GrpcControllerClient();
        Assertions.assertThrows(ControllerException.class,
            () -> client.registerBroker(null, name, address, instanceId));
    }

    @Test
    public void testRegisterBroker_leaderFailure() throws IOException, ControllerException {
        String name = "broker-name";
        String address = "localhost:1234";
        String instanceId = "i-ctrl";
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Node node = new Node();
        node.setId(1);
        node.setEpoch(1);
        Mockito.when(metadataStore.registerBrokerNode(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString())).thenThrow(new ControllerException(Code.MOCK_FAILURE_VALUE));
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            Assertions.assertThrows(ExecutionException.class,
                () -> client.registerBroker(String.format("localhost:%d", port), name, address, instanceId).get());

        }
    }

    @Test
    public void testCreateTopic() throws ControllerException, IOException, ExecutionException, InterruptedException {
        String topicName = "t1";
        int queueNum = 4;
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Mockito.when(metadataStore.createTopic(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(1L);
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            long topicId = client.createTopic(String.format("localhost:%d", port), topicName, queueNum).get();
            Assertions.assertEquals(1, topicId);
        }
    }

    @Test
    public void testCreateTopic_duplicate() throws ControllerException, IOException {
        String topicName = "t1";
        int queueNum = 4;
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Mockito.when(metadataStore.createTopic(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
            .thenThrow(new ControllerException(Code.DUPLICATED_VALUE, "Topic name is taken"));
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            Assertions.assertThrows(ExecutionException.class,
                () -> client.createTopic(String.format("localhost:%d", port), topicName, queueNum).get());
        }
    }

    @Test
    public void testDeleteTopic() throws ControllerException, IOException, ExecutionException, InterruptedException {
        long topicId = 1;
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Mockito.doNothing().when(metadataStore).deleteTopic(ArgumentMatchers.anyLong());
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            client.deleteTopic(String.format("localhost:%d", port), topicId).get();
        }
    }

    @Test
    public void testDeleteTopic_NotFound() throws ControllerException, IOException, ExecutionException, InterruptedException {
        long topicId = 1;
        MetadataStore metadataStore = Mockito.mock(MetadataStore.class);
        Mockito.doThrow(new ControllerException(Code.NOT_FOUND_VALUE, "Not found")).when(metadataStore).deleteTopic(ArgumentMatchers.anyLong());
        ControllerServiceImpl svc = new ControllerServiceImpl(metadataStore);
        try (ControllerTestServer testServer = new ControllerTestServer(0, svc)) {
            testServer.start();
            int port = testServer.getPort();
            ControllerClient client = new GrpcControllerClient();
            Assertions.assertThrows(ExecutionException.class, () -> client.deleteTopic(String.format("localhost:%d", port), topicId).get());
        }
    }
}