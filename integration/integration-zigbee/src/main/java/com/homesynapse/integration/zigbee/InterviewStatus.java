/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Tracks the interview completion state per Zigbee device.
 *
 * <p>The interview pipeline (Doc 08 §3.4) gathers device metadata through a sequence
 * of ZDO and ZCL queries (Node Descriptor, Active Endpoints, Simple Descriptors, Basic
 * cluster attributes). The interview may complete fully, partially (if some steps fail),
 * or remain pending if not yet attempted.
 *
 * <p>Partial interviews produce {@code device_discovered} events with whatever metadata
 * was gathered; the Device Model decides adoptability based on the available information.
 *
 * <p>Thread-safe: enum.
 *
 * @see InterviewResult
 * @see ZigbeeDeviceRecord
 */
public enum InterviewStatus {

    /** All interview steps succeeded. Full device metadata is available. */
    COMPLETE,

    /**
     * Some interview steps failed. The device may have limited capabilities
     * based on the metadata that was successfully gathered.
     */
    PARTIAL,

    /** Interview not yet attempted or currently in progress. */
    PENDING
}
