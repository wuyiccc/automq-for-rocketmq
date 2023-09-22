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
CREATE TABLE IF NOT EXISTS lease
(
    node_id         INT      NOT NULL,
    epoch           INT      NOT NULL DEFAULT 0,
    expiration_time DATETIME NOT NULL
);

INSERT INTO lease(node_id, epoch, expiration_time)
VALUES (0, 0, TIMESTAMP('2023-01-01'));

CREATE TABLE IF NOT EXISTS node
(
    id          INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255),
    volume_id   VARCHAR(255),
    hostname    VARCHAR(255),
    vpc_id      VARCHAR(255),
    address     VARCHAR(255) NOT NULL,
    epoch       INT          NOT NULL DEFAULT 1,
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS topic
(
    id          BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    queue_num   INT          NOT NULL,
    status      tinyint  DEFAULT 0,
    create_time DATETIME DEFAULT current_timestamp,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_topic_name ON topic (name);

CREATE TABLE IF NOT EXISTS queue_assignment
(
    topic_id    BIGINT  NOT NULL,
    queue_id    INT     NOT NULL,
    src_node_id INT     NOT NULL,
    dst_node_id INT     NOT NULL,
    status      TINYINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS queue
(
    topic_id    BIGINT  NOT NULL,
    queue_id    INT     NOT NULL,
    stream_id   BIGINT  NOT NULL,
    stream_role TINYINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_queue_stream_id ON queue(stream_id);
CREATE UNIQUE INDEX idx_queue ON queue(topic_id, queue_id, stream_role);

CREATE TABLE IF NOT EXISTS consumer_group
(
    id          BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    status      TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_consumer_group_name ON consumer_group (name);

CREATE TABLE IF NOT EXISTS subscription
(
    id          BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT,
    group_id    BIGINT       NOT NULL,
    topic_id    BIGINT       NOT NULL,
    expression  VARCHAR(255) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_subscription_group_topic ON subscription (group_id, topic_id);

CREATE TABLE IF NOT EXISTS group_progress
(
    group_id     BIGINT NOT NULL,
    topic_id     BIGINT NOT NULL,
    queue_id     INT    NOT NULL,
    queue_offset BIGINT NOT NULL
);
CREATE UNIQUE INDEX idx_group_progress ON group_progress (group_id, topic_id, queue_id);

CREATE TABLE IF NOT EXISTS stream
(
    stream_id    BIGINT  NOT NULL PRIMARY KEY,
    epoch        BIGINT  NOT NULL,
    range_id     INT     NOT NULL,
    start_offset BIGINT  NOT NULL,
    state        TINYINT NOT NULL
);

CREATE TABLE IF NOT EXISTS `range`
(
    range_id     INT    NOT NULL PRIMARY KEY,
    stream_id    BIGINT NOT NULL,
    epoch        BIGINT NOT NULL,
    start_offset BIGINT NOT NULL,
    end_offset   BIGINT NOT NULL,
    broker_id    INT    NOT NULL
);

CREATE INDEX idx_range_stream_id ON `range` (stream_id);
CREATE INDEX idx_range_broker_id ON `range` (broker_id);

CREATE TABLE IF NOT EXISTS s3object
(
    object_id                     BIGINT  NOT NULL PRIMARY KEY,
    object_size                   BIGINT  NOT NULL,
    prepared_timestamp            BIGINT,
    committed_timestamp           BIGINT,
    expired_timestamp             BIGINT,
    marked_for_deletion_timestamp BIGINT,
    state                         TINYINT NOT NULL
);

CREATE TABLE IF NOT EXISTS s3streamobject (
    stream_id           BIGINT  NOT NULL,
    start_offset        BIGINT  NOT NULL,
    end_offset          BIGINT  NOT NULL,
    object_id           BIGINT  NOT NULL,
    object_size         BIGINT  NOT NULL,
    base_data_timestamp BIGINT,
    committed_timestamp BIGINT
);

CREATE INDEX idx_s3streamobject_stream_id ON s3streamobject (stream_id);
CREATE INDEX idx_s3streamobject_object_id ON s3streamobject (object_id);

CREATE TABLE IF NOT EXISTS s3walobject (
    sequence_id         BIGINT  NOT NULL PRIMARY KEY,
    broker_id           INT     NOT NULL,
    object_id           BIGINT  NOT NULL,
    sub_streams         text    NOT NULL,
    base_data_timestamp BIGINT,
    committed_timestamp BIGINT
);

CREATE INDEX idx_s3walobject_broker_id ON s3walobject (broker_id);
CREATE INDEX idx_s3walobject_object_id ON s3walobject (object_id);