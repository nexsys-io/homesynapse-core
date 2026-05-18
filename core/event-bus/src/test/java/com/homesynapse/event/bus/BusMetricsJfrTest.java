/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BusMetricsJfr} — verifies JFR custom event emission
 * for the seven canonical bus metrics (AMD-43 §3.6.2).
 *
 * <p>Uses {@code jdk.jfr.Recording} to capture emitted events to a temp file
 * and then parses them with {@code RecordingFile}. This validates both the
 * canonical {@code @Name}s and that the field values survive the JFR round
 * trip.</p>
 */
class BusMetricsJfrTest {

    /** Creates a new test instance. */
    BusMetricsJfrTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    void publishLatencyCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(metrics -> {
            metrics.recordPublishLatency(Duration.ofMillis(42));
        });

        assertThat(events)
                .as("JFR recording should contain at least one publish.latency event")
                .anySatisfy(e -> {
                    assertThat(e.getEventType().getName())
                            .isEqualTo("homesynapse.bus.publish.latency");
                    assertThat(e.getLong("latencyMicros"))
                            .as("latencyMicros for 42ms = 42_000")
                            .isEqualTo(42_000L);
                });
    }

    @Test
    void publisherBlockedCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(BusMetrics::incrementPublisherBlocked);

        assertThat(events).anySatisfy(e ->
                assertThat(e.getEventType().getName())
                        .isEqualTo("homesynapse.bus.publisher.blocked.count"));
    }

    @Test
    void queueDepthCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(metrics ->
                metrics.recordWriterQueueDepth(42));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType().getName())
                    .isEqualTo("homesynapse.bus.writer.queue.depth");
            assertThat(e.getInt("depth")).isEqualTo(42);
        });
    }

    @Test
    void subscriberLagCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(metrics ->
                metrics.recordSubscriberLag("sub-X", 17L, Duration.ofMillis(250)));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType().getName())
                    .isEqualTo("homesynapse.bus.subscriber.lag");
            assertThat(e.getString("subscriberId")).isEqualTo("sub-X");
            assertThat(e.getLong("lagEvents")).isEqualTo(17L);
            assertThat(e.getLong("lagMillis")).isEqualTo(250L);
        });
    }

    @Test
    void derivedWriteAcceptedCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(metrics ->
                metrics.recordDerivedWriteAccepted("sub-Y"));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType().getName())
                    .isEqualTo("homesynapse.bus.subscriber.derived_writes.accepted");
            assertThat(e.getString("subscriberId")).isEqualTo("sub-Y");
        });
    }

    @Test
    void derivedWriteParkedCommitsJfrEvent() throws Exception {
        List<RecordedEvent> events = recordWhile(metrics ->
                metrics.recordDerivedWriteParked("sub-Z"));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType().getName())
                    .isEqualTo("homesynapse.bus.subscriber.derived_writes.parked");
            assertThat(e.getString("subscriberId")).isEqualTo("sub-Z");
        });
    }

    @Test
    void noopImplementationDoesNotThrow() {
        BusMetrics noop = BusMetrics.noop();

        // All methods on the no-op should be safe to call with any arguments.
        noop.recordPublishLatency(Duration.ofMillis(1));
        noop.incrementPublisherBlocked();
        noop.recordWriterQueueDepth(0);
        noop.recordSubscriberLag("sub", 0L, Duration.ZERO);
        noop.recordDerivedWriteAccepted("sub");
        noop.recordDerivedWriteParked("sub");
    }

    @Test
    void jfrFactoryReturnsNonNullDistinctInstances() {
        BusMetrics a = BusMetrics.jfr();
        BusMetrics b = BusMetrics.jfr();

        assertThat(a).isNotNull().isNotSameAs(b);
    }

    @Test
    void noopFactoryReturnsSingleton() {
        BusMetrics a = BusMetrics.noop();
        BusMetrics b = BusMetrics.noop();

        assertThat(a).isSameAs(b);
    }

    /**
     * Runs a JFR recording of all bus-metric event types while the given action
     * executes against a fresh {@link BusMetricsJfr} instance, then returns the
     * recorded events.
     *
     * @param action the action to execute under recording
     * @return the recorded events
     * @throws Exception if the recording or parsing fails
     */
    private static List<RecordedEvent> recordWhile(MetricsAction action) throws Exception {
        Path dump = Files.createTempFile("hs-bus-metrics-test-", ".jfr");
        BusMetrics metrics = BusMetrics.jfr();
        try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
            recording.enable(BusPublishLatencyEvent.class);
            recording.enable(BusPublisherBlockedEvent.class);
            recording.enable(BusWriterQueueDepthEvent.class);
            recording.enable(BusSubscriberLagEvent.class);
            recording.enable(BusWriteAcceptedEvent.class);
            recording.enable(BusWriteParkedEvent.class);
            recording.start();
            try {
                action.run(metrics);
            } finally {
                recording.stop();
                recording.dump(dump);
            }
        }
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(dump)) {
            while (rf.hasMoreEvents()) {
                events.add(rf.readEvent());
            }
        }
        Files.deleteIfExists(dump);
        return events;
    }

    @FunctionalInterface
    private interface MetricsAction {
        void run(BusMetrics metrics);
    }
}
