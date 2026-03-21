/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Tuya datapoint codec for devices using cluster 0xEF00.
 *
 * <p>All TS0601-model Tuya devices use this codec. The Tuya DP (datapoint) frame format
 * is: {@code [Header 2B] [DP1] [DP2] ... [DPN]} where each DP element is structured as
 * {@code [DPID 1B] [Type 1B] [Length 2B BE] [Value NB BE]}. A single frame can contain
 * multiple concatenated DPs.
 *
 * <p>Phase 3 implements parsing and DP-to-capability mapping via
 * {@link TuyaDatapointMapping}. Phase 2 defines this as a marker subtype of
 * {@link ManufacturerCodec} to establish the sealed hierarchy.
 *
 * <p>Doc 08 §3.8.
 *
 * <p>Thread-safe: implementations must be stateless or thread-safe.
 *
 * @see ManufacturerCodec
 * @see TuyaDatapointMapping
 * @see TuyaDpType
 */
public non-sealed interface TuyaDpCodec extends ManufacturerCodec {
}
