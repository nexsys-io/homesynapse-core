/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import com.homesynapse.platform.HealthReporter;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier-1 (systemd) {@link HealthReporter} that reports lifecycle state to the service
 * manager via {@code sd_notify} datagrams sent to the {@code $NOTIFY_SOCKET} socket
 * (LTD-13).
 *
 * <p>Each reporting method maps to one {@code sd_notify} assignment:
 * {@code reportReady()} → {@code READY=1}, {@code reportWatchdog()} → {@code WATCHDOG=1},
 * {@code reportStopping()} → {@code STOPPING=1}, {@code reportStatus(msg)} →
 * {@code STATUS=<msg>}. {@code READY=1} is sent at most once (constraint C12-03);
 * subsequent {@link #reportReady()} calls are ignored.</p>
 *
 * <p>Sends are best-effort and non-blocking ("send-and-forget"): the datagram protocol
 * has no reply, and a send failure is logged at WARN rather than propagated, so a
 * transient socket error never aborts the lifecycle or watchdog loop. Persistent failure
 * to heartbeat is handled by the service manager, which restarts the process.</p>
 *
 * <p>Thread-safe: sends are serialised with a {@link ReentrantLock} (LTD-11; never
 * {@code synchronized}), so {@link #reportStatus(String)} and the others may be called
 * from any thread.</p>
 *
 * <p>Selection of this implementation versus {@link NoOpHealthReporter} — including
 * reading {@code $NOTIFY_SOCKET} and deciding the deployment tier — is the composition
 * root's responsibility (lifecycle / M13) and is deliberately not performed here.</p>
 *
 * @see HealthReporter
 * @see NoOpHealthReporter
 */
public final class SystemdHealthReporter implements HealthReporter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SystemdHealthReporter.class);

    private final NotifyTransport transport;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean readySent = new AtomicBoolean(false);

    /**
     * Creates a reporter bound to the {@code AF_UNIX} datagram socket named by
     * {@code $NOTIFY_SOCKET}.
     *
     * @param notifySocketName the {@code $NOTIFY_SOCKET} value; a leading {@code @}
     *                         denotes an abstract-namespace socket
     * @throws IOException              if the datagram socket cannot be opened
     * @throws IllegalArgumentException if {@code notifySocketName} is {@code null} or blank
     */
    public SystemdHealthReporter(String notifySocketName) throws IOException {
        if (notifySocketName == null || notifySocketName.isBlank()) {
            throw new IllegalArgumentException("NOTIFY_SOCKET name must not be null or blank");
        }
        this.transport = new UnixDatagramTransport(notifySocketName);
    }

    /**
     * Creates a reporter over an injected transport, used by tests to assert the
     * {@code sd_notify} protocol without a real {@code AF_UNIX} datagram socket.
     *
     * @param transport the datagram transport, never {@code null}
     */
    SystemdHealthReporter(NotifyTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public void reportReady() {
        if (!readySent.compareAndSet(false, true)) {
            log.debug("reportReady() invoked more than once; ignoring the repeat");
            return;
        }
        send("READY=1");
    }

    @Override
    public void reportWatchdog() {
        send("WATCHDOG=1");
    }

    @Override
    public void reportStopping() {
        send("STOPPING=1");
    }

    @Override
    public void reportStatus(String message) {
        Objects.requireNonNull(message, "message");
        send("STATUS=" + message);
    }

    /**
     * Closes the underlying datagram socket. Invoked by the composition root during final
     * shutdown; idempotent at the channel level.
     *
     * @throws IOException if the socket cannot be closed
     */
    @Override
    public void close() throws IOException {
        transport.close();
    }

    private void send(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        lock.lock();
        try {
            transport.send(payload);
        } catch (IOException e) {
            log.warn("sd_notify send failed for '{}': {}", message, e.toString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Seam over the datagram send so the {@code sd_notify} message protocol and lifecycle
     * semantics can be unit-tested without a real {@code AF_UNIX} {@code SOCK_DGRAM}
     * socket (which the pure JDK cannot bind for receiving).
     */
    interface NotifyTransport extends AutoCloseable {

        void send(byte[] payload) throws IOException;

        @Override
        void close() throws IOException;
    }

    /**
     * Production transport: an {@code AF_UNIX} {@code SOCK_DGRAM} channel addressed by
     * {@code $NOTIFY_SOCKET}.
     */
    private static final class UnixDatagramTransport implements NotifyTransport {

        private final DatagramChannel channel;
        private final SocketAddress address;

        UnixDatagramTransport(String socketName) throws IOException {
            this.address = resolveAddress(socketName);
            try {
                this.channel = DatagramChannel.open(StandardProtocolFamily.UNIX);
            } catch (UnsupportedOperationException e) {
                // OpenJDK 21 implements Unix-domain stream sockets only (JEP 380); AF_UNIX
                // SOCK_DGRAM is unsupported, so the opaque UOE is reframed with the actual
                // cause and the remediation owner (composition root / M13).
                throw new IllegalStateException(
                        "sd_notify AF_UNIX SOCK_DGRAM transport is unsupported on this JDK "
                                + "(Unix-domain stream sockets only); a native binding or a "
                                + "systemd-notify fallback is required — deferred to M13", e);
            }
        }

        @Override
        public void send(byte[] payload) throws IOException {
            channel.send(ByteBuffer.wrap(payload), address);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        private static SocketAddress resolveAddress(String socketName) {
            // Abstract-namespace sockets are advertised with a leading '@', which maps to
            // a leading NUL byte in the address (the sd_notify convention).
            String path = socketName.startsWith("@")
                    ? "\0" + socketName.substring(1)
                    : socketName;
            return UnixDomainSocketAddress.of(path);
        }
    }
}
