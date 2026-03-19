/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Declares the I/O model an integration adapter uses to communicate with its
 * external protocol or device, which determines how the supervisor allocates
 * threads for the adapter (Doc 05 §3.2, §4.1).
 *
 * <p>The distinction matters because of Java 21 virtual thread semantics on
 * constrained hardware (Raspberry Pi 4/5, 4 cores). JNI calls from serial
 * libraries pin the carrier thread permanently, consuming one of only four
 * carriers. Virtual threads unmount from their carrier during blocking I/O,
 * allowing hundreds of concurrent network adapters without thread exhaustion.</p>
 *
 * <p>The adapter does not choose its thread — the supervisor allocates based on
 * the {@link IntegrationDescriptor#ioType()} declared at discovery time.</p>
 *
 * @see IntegrationDescriptor
 * @see IntegrationAdapter#run()
 */
public enum IoType {

    /**
     * The adapter communicates via a serial port (e.g., UART for Zigbee coordinators).
     *
     * <p>Serial adapters run on a dedicated platform thread because JNI calls
     * from serial libraries (jSerialComm) pin the carrier thread. The adapter's
     * {@link IntegrationAdapter#run()} method reads from the serial port on this
     * platform thread and feeds a {@code BlockingQueue} that a virtual thread
     * drains for event processing.</p>
     */
    SERIAL,

    /**
     * The adapter communicates via network sockets (TCP, HTTP, MQTT, WebSocket).
     *
     * <p>Network adapters run on a virtual thread. Blocking socket I/O unmounts
     * the virtual thread from its carrier, allowing other virtual threads to
     * proceed. This enables hundreds of concurrent network adapters on a
     * Raspberry Pi without thread pool exhaustion.</p>
     */
    NETWORK
}
