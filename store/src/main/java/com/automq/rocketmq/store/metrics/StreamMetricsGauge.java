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

package com.automq.rocketmq.store.metrics;

import com.automq.stream.s3.metrics.Gauge;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import java.util.Map;
import java.util.function.Supplier;

public class StreamMetricsGauge extends BaseStreamMetrics {

    public StreamMetricsGauge(String type, String name, Map<String, String> tags,
        Meter meter, Supplier<AttributesBuilder> attributesBuilderSupplier, Gauge gauge) {
        super(type, name, tags, meter, attributesBuilderSupplier);
        this.meter.gaugeBuilder(this.metricsName)
            .ofLongs()
            .buildWithCallback(measurement -> measurement.record(gauge.value(), newAttributesBuilder().build()));
    }
}