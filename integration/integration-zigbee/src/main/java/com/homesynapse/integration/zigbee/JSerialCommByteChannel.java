/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Objects;

/**
 * The jSerialComm-backed production implementation of {@link SerialByteChannel} —
 * one of the seam's exactly two implementations (D-M92-3; the other is the test fake).
 *
 * <p>Serial parameters per Doc 08 §3.3 / the bench corpus: 115200 baud, 8N1, flow
 * control None. Reads use {@code TIMEOUT_READ_SEMI_BLOCKING} so every read is
 * timeout-bounded — jSerialComm's fully blocking reads do not respond cleanly to
 * {@code Thread.interrupt()}, and a bounded read is what keeps close/abandon from
 * hanging the serial platform thread forever (W4). {@code closePort()} from another
 * thread unblocks an in-flight read.
 *
 * <p>jSerialComm ≥ 2.10.0 uses {@code ReentrantLock} internally rather than monitor
 * locking — the catalog version floor exists for exactly this (LTD-11).
 *
 * <p>This class is deliberately thin: all protocol logic lives behind the seam and is
 * unit-tested against the fake. Real-silicon contact happens at M9.4 bench acceptance.
 *
 * <p>Not thread-safe: single-threaded access by the transport thread
 * ({@code close()} from the shutdown path excepted, per jSerialComm's own locking).
 */
final class JSerialCommByteChannel implements SerialByteChannel {

    /** Serial line rate (Doc 08 §3.3; bench corpus A14). */
    static final int BAUD_RATE = 115200;
    private static final int DRAIN_READ_TIMEOUT_MILLIS = 1;

    private final SerialPort port;

    private JSerialCommByteChannel(SerialPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * Configures {@code port} (115200 8N1, no flow control) and opens it.
     *
     * @param port the serial port, never {@code null}
     * @return the opened channel
     * @throws TransportFailureException if the port cannot be opened
     */
    static JSerialCommByteChannel open(SerialPort port) {
        Objects.requireNonNull(port, "port");
        port.setBaudRate(BAUD_RATE);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!port.openPort()) {
            throw new TransportFailureException(
                    "failed to open serial port " + port.getSystemPortPath());
        }
        return new JSerialCommByteChannel(port);
    }

    @Override
    public int read(byte[] buffer, long maxWaitMillis) {
        int bounded = (int) Math.max(1, Math.min(maxWaitMillis, Integer.MAX_VALUE));
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, bounded, 0);
        return port.readBytes(buffer, buffer.length);
    }

    @Override
    public void write(byte[] data) {
        int written = port.writeBytes(data, data.length);
        if (written != data.length) {
            throw new TransportFailureException(String.format(
                    "serial write failed on %s: wrote %d of %d bytes",
                    port.getSystemPortPath(), written, data.length));
        }
    }

    @Override
    public void flushInput() {
        byte[] scratch = new byte[256];
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING, DRAIN_READ_TIMEOUT_MILLIS, 0);
        while (port.readBytes(scratch, scratch.length) > 0) {
            // Draining stale bytes; loop ends on timeout (0) or error (-1).
        }
    }

    @Override
    public boolean isOpen() {
        return port.isOpen();
    }

    @Override
    public void close() {
        port.closePort();
    }
}
