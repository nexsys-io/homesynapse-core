/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Xiaomi/Aqara TLV codec for devices reporting via manufacturer-specific attributes.
 *
 * <p>Xiaomi and Aqara devices report sensor data on attribute 0xFF01 of the Basic cluster
 * (manufacturer code 0x115F) or attribute 0x00F7 of cluster 0xFCC0. The TLV
 * (Tag-Length-Value) format is: {@code [Tag 1B] [ZCL Type 1B] [Value NB]} — tags use
 * standard ZCL type IDs for value encoding, allowing the adapter's ZCL type codec to be
 * reused for value decoding.
 *
 * <p>Documented tags: 0x01 (battery mV), 0x03 (device temp), 0x64 (model-dependent primary
 * measurement), 0x65 (humidity), 0x66 (pressure). Tag 0x64 interpretation requires the
 * device model identifier from the interview.
 *
 * <p>Phase 3 implements parsing and tag-to-capability mapping via {@link XiaomiTagMapping}.
 * Phase 2 defines this as a marker subtype of {@link ManufacturerCodec} to establish the
 * sealed hierarchy.
 *
 * <p>Doc 08 §3.9.
 *
 * <p>Thread-safe: implementations must be stateless or thread-safe.
 *
 * @see ManufacturerCodec
 * @see XiaomiTagMapping
 */
public non-sealed interface XiaomiTlvCodec extends ManufacturerCodec {
}
