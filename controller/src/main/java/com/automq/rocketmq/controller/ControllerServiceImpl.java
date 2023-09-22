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

package com.automq.rocketmq.controller;

import apache.rocketmq.controller.v1.Code;
import apache.rocketmq.controller.v1.CommitOffsetReply;
import apache.rocketmq.controller.v1.CommitOffsetRequest;
import apache.rocketmq.controller.v1.ControllerServiceGrpc;
import apache.rocketmq.controller.v1.CreateTopicReply;
import apache.rocketmq.controller.v1.CreateTopicRequest;
import apache.rocketmq.controller.v1.DeleteTopicReply;
import apache.rocketmq.controller.v1.DeleteTopicRequest;
import apache.rocketmq.controller.v1.DescribeTopicReply;
import apache.rocketmq.controller.v1.DescribeTopicRequest;
import apache.rocketmq.controller.v1.HeartbeatReply;
import apache.rocketmq.controller.v1.HeartbeatRequest;
import apache.rocketmq.controller.v1.ListMessageQueueReassignmentsReply;
import apache.rocketmq.controller.v1.ListMessageQueueReassignmentsRequest;
import apache.rocketmq.controller.v1.ListTopicMessageQueueAssignmentsReply;
import apache.rocketmq.controller.v1.ListTopicMessageQueueAssignmentsRequest;
import apache.rocketmq.controller.v1.ListTopicsReply;
import apache.rocketmq.controller.v1.ListTopicsRequest;
import apache.rocketmq.controller.v1.NodeRegistrationReply;
import apache.rocketmq.controller.v1.NodeRegistrationRequest;
import apache.rocketmq.controller.v1.NodeUnregistrationReply;
import apache.rocketmq.controller.v1.NodeUnregistrationRequest;
import apache.rocketmq.controller.v1.NotifyMessageQueuesAssignableReply;
import apache.rocketmq.controller.v1.NotifyMessageQueuesAssignableRequest;
import apache.rocketmq.controller.v1.ReassignMessageQueueReply;
import apache.rocketmq.controller.v1.ReassignMessageQueueRequest;
import apache.rocketmq.controller.v1.Status;
import apache.rocketmq.controller.v1.Topic;
import apache.rocketmq.controller.v1.UpdateTopicRequest;
import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.metadata.MetadataStore;
import com.automq.rocketmq.controller.metadata.database.dao.Node;
import com.google.protobuf.TextFormat;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControllerServiceImpl extends ControllerServiceGrpc.ControllerServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerServiceImpl.class);

    private final MetadataStore metadataStore;

    public ControllerServiceImpl(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    @Override
    public void registerNode(NodeRegistrationRequest request,
        StreamObserver<NodeRegistrationReply> responseObserver) {
        try {
            Node node = metadataStore.registerBrokerNode(request.getBrokerName(), request.getAddress(),
                request.getInstanceId());
            NodeRegistrationReply reply = NodeRegistrationReply.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.OK).build())
                .setId(node.getId())
                .setEpoch(node.getEpoch())
                .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (ControllerException e) {
            responseObserver.onError(e);
        }
    }

    @Override
    public void unregisterNode(NodeUnregistrationRequest request,
        StreamObserver<NodeUnregistrationReply> responseObserver) {
        super.unregisterNode(request, responseObserver);
    }

    @Override
    public void processHeartbeat(HeartbeatRequest request,
        StreamObserver<HeartbeatReply> responseObserver) {
        LOGGER.trace("Received HeartbeatRequest {}", TextFormat.shortDebugString(request));

        Status status = Status.newBuilder().setCode(Code.OK).build();
        HeartbeatReply reply = HeartbeatReply.newBuilder().setStatus(status).build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void createTopic(CreateTopicRequest request, StreamObserver<CreateTopicReply> responseObserver) {
        LOGGER.trace("Received CreateTopicRequest {}", TextFormat.shortDebugString(request));
        if (request.getCount() <= 0) {
            CreateTopicReply reply = CreateTopicReply.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.BAD_REQUEST).setMessage("Topic queue number needs to be positive").build())
                .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
            return;
        }

        try {
            long topicId = this.metadataStore.createTopic(request.getTopic(), request.getCount());
            CreateTopicReply reply = CreateTopicReply.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.OK).build())
                .setTopicId(topicId)
                .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (ControllerException e) {
            if (Code.DUPLICATED_VALUE == e.getErrorCode()) {
                CreateTopicReply reply = CreateTopicReply.newBuilder()
                    .setStatus(Status.newBuilder()
                        .setCode(Code.DUPLICATED)
                        .setMessage(String.format("%s is already taken", request.getTopic()))
                        .build())
                    .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
                return;
            }
            responseObserver.onError(e);
        }
    }

    @Override
    public void describeTopic(DescribeTopicRequest request, StreamObserver<DescribeTopicReply> responseObserver) {
        super.describeTopic(request, responseObserver);
    }

    @Override
    public void listAllTopics(ListTopicsRequest request, StreamObserver<ListTopicsReply> responseObserver) {
        super.listAllTopics(request, responseObserver);
    }

    @Override
    public void updateTopic(UpdateTopicRequest request, StreamObserver<Topic> responseObserver) {
        super.updateTopic(request, responseObserver);
    }

    @Override
    public void deleteTopic(DeleteTopicRequest request, StreamObserver<DeleteTopicReply> responseObserver) {
        super.deleteTopic(request, responseObserver);
    }

    @Override
    public void listTopicMessageQueues(ListTopicMessageQueueAssignmentsRequest request,
        StreamObserver<ListTopicMessageQueueAssignmentsReply> responseObserver) {
        super.listTopicMessageQueues(request, responseObserver);
    }

    @Override
    public void reassignMessageQueue(ReassignMessageQueueRequest request,
        StreamObserver<ReassignMessageQueueReply> responseObserver) {
        super.reassignMessageQueue(request, responseObserver);
    }

    @Override
    public void notifyMessageQueueAssignable(NotifyMessageQueuesAssignableRequest request,
        StreamObserver<NotifyMessageQueuesAssignableReply> responseObserver) {
        super.notifyMessageQueueAssignable(request, responseObserver);
    }

    @Override
    public void listMessageQueueReassignments(ListMessageQueueReassignmentsRequest request,
        StreamObserver<ListMessageQueueReassignmentsReply> responseObserver) {
        super.listMessageQueueReassignments(request, responseObserver);
    }

    @Override
    public void commitOffset(CommitOffsetRequest request, StreamObserver<CommitOffsetReply> responseObserver) {
        super.commitOffset(request, responseObserver);
    }
}