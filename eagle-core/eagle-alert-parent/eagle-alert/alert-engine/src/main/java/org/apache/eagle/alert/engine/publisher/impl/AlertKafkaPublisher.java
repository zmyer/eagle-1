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
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.eagle.alert.engine.publisher.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.eagle.alert.engine.coordinator.Publishment;
import org.apache.eagle.alert.engine.model.AlertStreamEvent;
import org.apache.eagle.alert.engine.publisher.PublishConstants;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

public class AlertKafkaPublisher extends AbstractPublishPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AlertKafkaPublisher.class);
    private static final long MAX_TIMEOUT_MS = 60000;

    @SuppressWarnings("rawtypes")
    private KafkaProducer producer;
    private String brokerList;
    private String topic;
    private KafkaWriteMode mode = KafkaWriteMode.async;

    @Override
    @SuppressWarnings("rawtypes")
    public void init(Config config, Publishment publishment, Map conf) throws Exception {
        super.init(config, publishment, conf);

        if (publishment.getProperties() != null) {
            Map<String, Object> publishConfig = new HashMap<>(publishment.getProperties());
            brokerList = ((String) publishConfig.get(PublishConstants.BROKER_LIST)).trim();
            producer = KafkaProducerManager.INSTANCE.getProducer(brokerList, publishConfig);
            topic = ((String) publishConfig.get(PublishConstants.TOPIC)).trim();
            mode = KafkaProducerManager.INSTANCE.getKafkaWriteMode(publishConfig);
        }
    }

    @Override
    public void onAlert(AlertStreamEvent event) throws Exception {
        if (producer == null) {
            LOG.warn("KafkaProducer is null due to the incorrect configurations");
            return;
        }

        this.emit(this.topic, this.dedup(event));
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void update(String dedupIntervalMin, Map<String, Object> pluginProperties) {
        deduplicator.setDedupIntervalMin(dedupIntervalMin);
        String newBrokerList = ((String) pluginProperties.get(PublishConstants.BROKER_LIST)).trim();
        String newTopic = ((String) pluginProperties.get(PublishConstants.TOPIC)).trim();
        if (!newBrokerList.equals(this.brokerList)) {
            if (producer != null) {
                producer.close();
            }
            brokerList = newBrokerList;
            KafkaProducer newProducer = null;
            try {
                newProducer = KafkaProducerManager.INSTANCE.getProducer(brokerList, pluginProperties);
            } catch (Exception e) {
                LOG.error("Create KafkaProducer failed with configurations: {}", pluginProperties);
            }
            producer = newProducer;
        }
        topic = newTopic;
    }

    @Override
    public void close() {
        producer.close();
    }

    @SuppressWarnings( {"rawtypes", "unchecked"})
    protected void emit(String topic, List<AlertStreamEvent> outputEvents) {
        // we need to check producer here since the producer is invisable to extended kafka publisher
        if (producer == null) {
            LOG.warn("KafkaProducer is null due to the incorrect configurations");
            return;
        }
        if (outputEvents == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Alert stream events list in publishment is empty");
            }
            return;
        }
        this.status = new PublishStatus();
        try {
            for (AlertStreamEvent outputEvent : outputEvents) {
                ProducerRecord record = createRecord(outputEvent, topic);
                if (record == null) {
                    LOG.error("Alert serialize return null, ignored message! ");
                    return;
                }
                if (mode == KafkaWriteMode.sync) {
                    Future<?> future = producer.send(record);
                    future.get(MAX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    succeed(mode, "");
                } else {
                    producer.send(record, new Callback() {
                        @Override
                        public void onCompletion(RecordMetadata metadata, Exception exception) {
                            if (exception != null) {
                                failOnException(String.format("Failed to send message to %s, due to:%s",
                                    brokerList, exception), exception);
                                return;
                            }
                            succeed(mode, "");
                        }
                    });
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            failOnException(String.format("Failed to send message to %s, due to:%s", brokerList, e), e);
        } catch (Exception ex) {
            failOnException(String.format("Failed to send message to %s, due to:%s", brokerList, ex), ex);
        }
    }

    private void failOnException(String message, Exception e) {
        status.successful = false;
        status.errorMessage = message;
        LOG.error(status.errorMessage, e);
    }

    private void succeed(KafkaWriteMode mode, String message) {
        status.successful = true;
        status.errorMessage = "";
        if (LOG.isDebugEnabled()) {
            LOG.debug("Successfully send message to Kafka: {} in mode {}", brokerList, mode);
        }
    }

    protected String getTopic() {
        return this.topic;
    }

    private ProducerRecord<String, Object> createRecord(AlertStreamEvent event, String topic) throws Exception {
        Object o = serialzeEvent(event);
        if (o != null) {
            ProducerRecord<String, Object> record = new ProducerRecord<>(topic, o);
            return record;
        } else {
            return null;
        }
    }

    private Object serialzeEvent(AlertStreamEvent event) {
        return serializer.serialize(event);
    }

    @Override
    protected Logger getLogger() {
        return LOG;
    }
}
