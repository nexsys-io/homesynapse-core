/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Internal byte-level seam between the ASH/probe layers and the physical serial port
 * (D-M92-3).
 *
 * <p>Exactly two implementations exist by design: {@link JSerialCommByteChannel} (the
 * jSerialComm-backed production channel) and the test fake. Every protocol component
 * (ASH codec/session, EZSP layer, transport probe) is written against this seam, which
 * is what makes "zero real serial I/O in unit tests" structural rather than aspirational
 * — {@code NoRealIoExtension} gives no serial protection (pre-verification A13).
 *
 * <p>Reads are timeout-bounded (never indefinitely blocking) so that close/abandon can
 * never hang the dedicated serial platform thread (W4 — jSerialComm blocking reads do
 * not respond cleanly to {@code Thread.interrupt()}).
 *
 * <p><strong>{@link #isOpen()} lies on unplug</strong> (W5): a yanked USB device can keep
 * reporting open while reads return errors. Health decisions must never gate on
 * {@code isOpen()} alone — see {@link PortWatchdog}.
 *
 * <p>Not thread-safe: single-threaded access by the transport thread
 * ({@code IoType.SERIAL}, Doc 05 §3.2).
 *
 * @see JSerialCommByteChannel
 * @see AshSession
 * @see TransportProbe
 */
interface SerialByteChannel {

    /**
     * Reads available bytes into {@code buffer}, waiting up to {@code maxWaitMillis}.
     *
     * @param buffer the destination buffer, never {@code null}
     * @param maxWaitMillis the maximum time to wait for at least one byte
     * @return the number of bytes read ({@code 0} on timeout with no data), or
     *         {@code -1} on a read error / dead port
     */
    int read(byte[] buffer, long maxWaitMillis);

    /**
     * Writes all bytes to the serial port.
     *
     * @param data the bytes to transmit, never {@code null}
     * @throws TransportFailureException if the write fails or the port is dead
     */
    void write(byte[] data);

    /**
     * Discards any bytes currently pending in the receive buffer.
     */
    void flushInput();

    /**
     * Reports whether the underlying port believes it is open.
     *
     * <p>Diagnostic only — never the sole health source (W5).
     *
     * @return {@code true} if the port reports open
     */
    boolean isOpen();

    /**
     * Closes the channel. Idempotent: closing an already-closed channel has no effect.
     */
    void close();
}
