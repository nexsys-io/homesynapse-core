/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SystemdHealthReporter}. The {@code sd_notify} message protocol,
 * the once-only {@code READY=1} contract, error handling, and thread-safety are verified
 * through the injected {@link SystemdHealthReporter.NotifyTransport} seam — the pure JDK
 * cannot bind an {@code AF_UNIX} {@code SOCK_DGRAM} socket for receiving, so the datagram
 * payloads are captured rather than read off a real socket.
 */
@DisplayName("SystemdHealthReporter")
class SystemdHealthReporterTest {

    @Test
    @DisplayName("reportReady sends READY=1 exactly once")
    void reportReadyOnce() {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);

        reporter.reportReady();
        reporter.reportReady();

        assertThat(transport.messages()).containsExactly("READY=1");
    }

    @Test
    @DisplayName("reportWatchdog sends WATCHDOG=1 on every call")
    void reportWatchdog() {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);

        reporter.reportWatchdog();
        reporter.reportWatchdog();

        assertThat(transport.messages()).containsExactly("WATCHDOG=1", "WATCHDOG=1");
    }

    @Test
    @DisplayName("reportStopping sends STOPPING=1")
    void reportStopping() {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);

        reporter.reportStopping();

        assertThat(transport.messages()).containsExactly("STOPPING=1");
    }

    @Test
    @DisplayName("reportStatus sends STATUS=<message>")
    void reportStatus() {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);

        reporter.reportStatus("Phase 5: accepting connections");

        assertThat(transport.messages()).containsExactly("STATUS=Phase 5: accepting connections");
    }

    @Test
    @DisplayName("reportStatus rejects a null message")
    void reportStatusRejectsNull() {
        var reporter = new SystemdHealthReporter(new CapturingTransport());

        assertThatThrownBy(() -> reporter.reportStatus(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a failing transport does not propagate the I/O failure (send-and-forget)")
    void sendFailureIsNotPropagated() {
        var reporter = new SystemdHealthReporter(new FailingTransport());

        // None of these should throw — failures are logged, not propagated.
        reporter.reportReady();
        reporter.reportWatchdog();
        reporter.reportStopping();
        reporter.reportStatus("status");
    }

    @Test
    @DisplayName("concurrent reports are all delivered without loss")
    void concurrentReports() throws InterruptedException {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);
        int threadCount = 16;
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    reporter.reportWatchdog();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();

        assertThat(transport.messages()).hasSize(threadCount).containsOnly("WATCHDOG=1");
    }

    @Test
    @DisplayName("close closes the underlying transport")
    void closeClosesTransport() throws IOException {
        var transport = new CapturingTransport();
        var reporter = new SystemdHealthReporter(transport);

        reporter.close();

        assertThat(transport.isClosed()).isTrue();
    }

    @Test
    @DisplayName("rejects a blank NOTIFY_SOCKET name")
    void rejectsBlankSocketName() {
        assertThatThrownBy(() -> new SystemdHealthReporter(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a null NOTIFY_SOCKET name")
    void rejectsNullSocketName() {
        assertThatThrownBy(() -> new SystemdHealthReporter((String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Records each datagram payload as a UTF-8 string. */
    private static final class CapturingTransport implements SystemdHealthReporter.NotifyTransport {

        private final List<String> received = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        @Override
        public void send(byte[] payload) {
            received.add(new String(payload, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed = true;
        }

        List<String> messages() {
            return List.copyOf(received);
        }

        boolean isClosed() {
            return closed;
        }
    }

    /** Transport whose send always fails, to exercise the swallow-and-log path. */
    private static final class FailingTransport implements SystemdHealthReporter.NotifyTransport {

        @Override
        public void send(byte[] payload) throws IOException {
            throw new IOException("simulated sd_notify failure");
        }

        @Override
        public void close() {
            // Nothing to close.
        }
    }
}
