/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The deterministic test implementation of {@link SerialByteChannel} — the seam's
 * second implementation by design (D-M92-3). Zero real serial I/O; zero real waits:
 * an empty read ADVANCES the injected {@link TestClock} by the requested wait, so
 * every timeout path executes instantly and deterministically.
 *
 * <p>Two driving styles: enqueue inbound chunks directly ({@link #enqueue(byte[])}),
 * or install a write handler that computes responses from written bytes
 * ({@link #onWrite(Function)}) — the {@link FakeNcp} reactive simulator uses the
 * latter.
 */
final class FakeSerialByteChannel implements SerialByteChannel {

    private final TestClock clock;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final List<byte[]> writes = new ArrayList<>();

    private Function<byte[], List<byte[]>> writeHandler;
    private boolean reportOpen = true;
    private boolean dead;
    private boolean throwOnAnyIo;

    FakeSerialByteChannel(TestClock clock) {
        this.clock = clock;
    }

    /** Queues an inbound chunk for the next read. */
    void enqueue(byte[] chunk) {
        pending.add(chunk.clone());
    }

    /** Installs a reactive handler: written bytes → response chunks. */
    void onWrite(Function<byte[], List<byte[]>> handler) {
        this.writeHandler = handler;
    }

    /** Makes reads return {@code -1} (dead port) from now on. */
    void markDead() {
        this.dead = true;
    }

    /** Controls what {@link #isOpen()} reports (the W5 "isOpen lies" switch). */
    void reportOpen(boolean open) {
        this.reportOpen = open;
    }

    /** Makes EVERY channel method throw — the INV-RF-03 construction guard. */
    void throwOnAnyIo() {
        this.throwOnAnyIo = true;
    }

    /** Returns all writes in order. */
    List<byte[]> writes() {
        return writes;
    }

    /** Returns all written bytes concatenated. */
    byte[] allWrittenBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] write : writes) {
            out.writeBytes(write);
        }
        return out.toByteArray();
    }

    /** Clears recorded writes. */
    void clearWrites() {
        writes.clear();
    }

    @Override
    public int read(byte[] buffer, long maxWaitMillis) {
        failIfGuarded();
        if (dead) {
            return -1;
        }
        if (pending.isEmpty()) {
            // Deterministic timeout: no data will arrive, so the wait "elapses".
            clock.advance(Duration.ofMillis(Math.max(0, maxWaitMillis)));
            return 0;
        }
        byte[] chunk = pending.poll();
        if (chunk.length <= buffer.length) {
            System.arraycopy(chunk, 0, buffer, 0, chunk.length);
            return chunk.length;
        }
        System.arraycopy(chunk, 0, buffer, 0, buffer.length);
        byte[] rest = new byte[chunk.length - buffer.length];
        System.arraycopy(chunk, buffer.length, rest, 0, rest.length);
        pending.addFirst(rest);
        return buffer.length;
    }

    @Override
    public void write(byte[] data) {
        failIfGuarded();
        if (dead) {
            throw new TransportFailureException("fake channel is dead");
        }
        writes.add(data.clone());
        if (writeHandler != null) {
            List<byte[]> responses = writeHandler.apply(data.clone());
            if (responses != null) {
                responses.forEach(this::enqueue);
            }
        }
    }

    @Override
    public void flushInput() {
        failIfGuarded();
        pending.clear();
    }

    @Override
    public boolean isOpen() {
        return reportOpen;
    }

    @Override
    public void close() {
        reportOpen = false;
    }

    private void failIfGuarded() {
        if (throwOnAnyIo) {
            throw new AssertionError(
                    "channel I/O during construction phase (INV-RF-03 violation)");
        }
    }
}
