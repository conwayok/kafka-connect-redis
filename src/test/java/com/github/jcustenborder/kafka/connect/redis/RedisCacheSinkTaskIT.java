/**
 * Copyright © 2017 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.redis;

import com.github.jcustenborder.docker.junit5.Compose;
import com.github.jcustenborder.docker.junit5.Port;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.lettuce.core.KeyValue;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Compose(
    dockerComposePath = "src/test/resources/docker-compose.yml"
)
public class RedisCacheSinkTaskIT {
  private static final Logger log = LoggerFactory.getLogger(RedisCacheSinkTaskIT.class);


  RedisCacheSinkTask task;
  AtomicLong offset;

  @BeforeEach
  public void before() {
    this.task = new RedisCacheSinkTask();
    this.offset = new AtomicLong(1L);
  }

  public SinkRecord structWrite(
      TestLocation location,
      String topic,
      int partition,
      AtomicLong offset
  ) {
    return new SinkRecord(topic, partition,
        Schema.STRING_SCHEMA, location.ident(),
        Schema.STRING_SCHEMA, location.region(),
        offset.incrementAndGet());
  }

  public SinkRecord structDelete(
      TestLocation location,
      String topic,
      int partition,
      AtomicLong offset
  ) {
    return new SinkRecord(topic, partition,
        Schema.STRING_SCHEMA, location.ident(),
        null, null,
        offset.incrementAndGet());
  }

  @Test
  public void emptyAssignment(@Port(container = "redis", internalPort = 6379) InetSocketAddress address) throws ExecutionException, InterruptedException {
    log.info("address = {}", address);
    final String topic = "putWrite";
    SinkTaskContext context = mock(SinkTaskContext.class);
    when(context.assignment()).thenReturn(ImmutableSet.of());
    this.task.initialize(context);
    this.task.start(
        ImmutableMap.of(RedisSinkConnectorConfig.HOSTS_CONFIG, String.format("%s:%s", address.getHostString(), address.getPort()))
    );
  }

  @Test
  public void putEmpty(@Port(container = "redis", internalPort = 6379) InetSocketAddress address) throws ExecutionException, InterruptedException {
    log.info("address = {}", address);
    final String topic = "putWrite";
    SinkTaskContext context = mock(SinkTaskContext.class);
    when(context.assignment()).thenReturn(ImmutableSet.of(new TopicPartition(topic, 1)));
    this.task.initialize(context);
    this.task.start(
        ImmutableMap.of(RedisSinkConnectorConfig.HOSTS_CONFIG, String.format("%s:%s", address.getHostString(), address.getPort()))
    );

    this.task.put(ImmutableList.of());
  }

  @Test
  public void putWrite(@Port(container = "redis", internalPort = 6379) InetSocketAddress address) throws ExecutionException, InterruptedException, IOException, TimeoutException {
    log.info("address = {}", address);
    final String topic = "putWrite";
    SinkTaskContext context = mock(SinkTaskContext.class);
    when(context.assignment()).thenReturn(ImmutableSet.of(new TopicPartition(topic, 1)));
    this.task.initialize(context);
    this.task.start(
        ImmutableMap.of(RedisSinkConnectorConfig.HOSTS_CONFIG, String.format("%s:%s", address.getHostString(), address.getPort()))
    );

    final List<TestLocation> locations = TestLocation.loadLocations();
    final AtomicLong offset = new AtomicLong(1L);
    final List<SinkRecord> writes = locations.stream()
        .map(l -> structWrite(l, topic, 1, offset))
        .collect(Collectors.toList());
    byte[][] keys = locations.stream()
        .map(l -> l.ident.getBytes(Charsets.UTF_8))
        .toArray(byte[][]::new);
    this.task.put(writes);

    List<KeyValue<String, String>> results = this.task.session.asyncCommands().mget(keys).get(30, TimeUnit.SECONDS)
        .stream()
        .map(kv -> KeyValue.fromNullable(
            new String(kv.getKey(), Charsets.UTF_8),
            new String(kv.getValue(), Charsets.UTF_8)
            )
        ).collect(Collectors.toList());
    assertEquals(keys.length, results.size());

    results.forEach(kv -> {
      Optional<TestLocation> location = locations.stream()
          .filter(l -> l.ident.equals(kv.getKey()))
          .findAny();
      assertTrue(location.isPresent(), "location should have existed.");
      assertEquals(location.get().region, kv.getValue());
    });
  }

  @Test
  public void putDelete(@Port(container = "redis", internalPort = 6379) InetSocketAddress address) throws ExecutionException, InterruptedException, IOException, TimeoutException {
    log.info("address = {}", address);
    final String topic = "putDelete";
    SinkTaskContext context = mock(SinkTaskContext.class);
    when(context.assignment()).thenReturn(ImmutableSet.of(new TopicPartition(topic, 1)));
    this.task.initialize(context);
    this.task.start(
        ImmutableMap.of(RedisSinkConnectorConfig.HOSTS_CONFIG, String.format("%s:%s", address.getHostString(), address.getPort()))
    );

    final List<TestLocation> locations = TestLocation.loadLocations();
    final AtomicLong offset = new AtomicLong(1L);
    final List<SinkRecord> writes = locations.stream()
        .map(l -> structWrite(l, topic, 1, offset))
        .collect(Collectors.toList());
    final List<SinkRecord> deletes = locations.stream()
        .map(l -> structDelete(l, topic, 1, offset))
        .collect(Collectors.toList());
    byte[][] keys = locations.stream()
        .map(l -> l.ident.getBytes(Charsets.UTF_8))
        .toArray(byte[][]::new);

    this.task.put(writes);

    long count = this.task.session.asyncCommands().exists(keys).get(30, TimeUnit.SECONDS);
    assertEquals(locations.size(), count);
    this.task.put(deletes);
    count = this.task.session.asyncCommands().exists(keys).get(30, TimeUnit.SECONDS);
    assertEquals(0, count);
  }

  @AfterEach
  public void after() {
    if (null != this.task) {
      this.task.stop();
    }
  }

}