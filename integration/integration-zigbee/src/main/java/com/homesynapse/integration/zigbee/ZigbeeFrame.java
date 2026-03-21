/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Transport-level frame representation for Zigbee coordinator communication.
 *
 * <p>This sealed interface enables exhaustive pattern matching in the protocol layer's
 * frame dispatch logic. The two variants — {@link ZnpFrame} for ZNP (Z-Stack Network
 * Processor) coordinators and {@link EzspFrame} for EZSP (EmberZNet Serial Protocol)
 * coordinators — have incompatible structures and are dispatched separately.
 *
 * <p>Frames are shared between the transport layer (dedicated platform thread for serial
 * I/O) and the protocol layer (virtual threads) via a {@code BlockingQueue<ZigbeeFrame>}.
 * Both variants are immutable records with defensively copied byte arrays, ensuring
 * thread-safe handoff between threads.
 *
 * <p>Doc 08 §4.2.
 *
 * <p>Thread-safe: immutable variants.
 *
 * @see ZnpFrame
 * @see EzspFrame
 * @see ZclFrame
 * @see CoordinatorTransport
 */
public sealed interface ZigbeeFrame permits ZnpFrame, EzspFrame {
}
