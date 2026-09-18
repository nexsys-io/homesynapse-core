/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Simple Metering cluster (0x0702) handler (ENERGY-READ R4):
 * {@code CurrentSummationDelivered} (uint48) → {@code energy_wh} =
 * {@code raw × multiplier / divisor × 1000} — when, and only when, the device
 * declared its {@code UnitOfMeasure} as kWh. Any other unit, or a unit the
 * device never declared, emits NOTHING with a debug naming the unit: a
 * summation in an unknown unit is not an energy, and no unit is guessed.
 *
 * <p><strong>Constructed WITH a formatting — never without.</strong> The
 * handler table attaches this handler only when the summation pair is known
 * ({@link ClusterHandlers#forDevice}). The raw integer rides
 * {@code rawProtocolValue} and the formatting that scaled it rides
 * {@code rawProtocolUnit} ({@code "mult=1 div=1000000 unit=0x0"}) — a derived
 * value is never presented without its observation.
 *
 * <p>The uint48 arrives as a {@code Long}; the scale happens in {@code double}
 * at the last step only, with ONE division ({@code raw × mult × 1000 / div}).
 * {@code InstantaneousDemand} (0x0400, int24) is NOT decoded by the codec and
 * is out of scope — {@code ActivePower} on 0x0B04 is the power source.
 *
 * <p>No command path in this lane: {@code energy_meter} declares
 * {@code reset_meter}, and the inherited {@code buildCommand} throws
 * {@code UnsupportedOperationException} for it (PERMANENT at the supervisor).
 *
 * <p>Thread-safe: stateless behavior over the immutable formatting binding.
 */
final class MeteringHandler extends ZigbeeClusterHandler {

    // ── BENCH-VERIFY constants (ENERGY-READ) ────────────────────────────────
    // ZCL-derived (the Zigbee Cluster Library Specification, document 07-5123,
    // the Simple Metering chapter: the reading set 0x00xx and the formatting
    // set 0x03xx; corroborated by the field's cluster tables) and NOT yet
    // silicon-verified: no metering plug has joined (THE ADOPTION FENCE). The
    // first adoption is the measurement (the measurement record's G4 rows).

    static final int CLUSTER_ID = 0x0702;
    /** {@code CurrentSummationDelivered} (uint48), in {@code UnitOfMeasure} × Multiplier / Divisor. */
    static final int ATTRIBUTE_CURRENT_SUMMATION_DELIVERED = 0x0000;
    /** {@code UnitOfMeasure} (enum8); 0x00 = kWh. */
    static final int ATTRIBUTE_UNIT_OF_MEASURE = 0x0300;
    /** {@code Multiplier} (uint24). */
    static final int ATTRIBUTE_MULTIPLIER = 0x0301;
    /** {@code Divisor} (uint24). */
    static final int ATTRIBUTE_DIVISOR = 0x0302;
    /** {@code SummationFormatting} (map8) — read and carried by the frame; display-only. */
    static final int ATTRIBUTE_SUMMATION_FORMATTING = 0x0303;
    /** ZCL data type of {@code CurrentSummationDelivered}: uint48. */
    static final int DATA_TYPE_CURRENT_SUMMATION = 0x25;
    /** The uint48 invalid sentinel. */
    static final long INVALID_UINT48 = 0xFFFF_FFFF_FFFFL;
    /** kWh → Wh. */
    private static final double WATT_HOURS_PER_KILOWATT_HOUR = 1000.0;

    private static final Logger log =
            LoggerFactory.getLogger(MeteringHandler.class);

    private final IEEEAddress device;
    private final MeteringFormatting formatting;

    /**
     * Binds the handler to its device and the formatting the device declared.
     *
     * @param device the device this handler instance serves, never {@code null}
     * @param clock the time source, never {@code null}
     * @param formatting the read formatting, never {@code null}
     */
    MeteringHandler(IEEEAddress device, Clock clock,
            MeteringFormatting formatting) {
        super(device, clock);
        this.device = device;
        this.formatting = Objects.requireNonNull(formatting, "formatting");
    }

    /**
     * The formatting attributes of the ONE read frame, in id order (a fresh
     * array — the constant block stays immutable).
     */
    static int[] formattingAttributes() {
        return new int[] {ATTRIBUTE_UNIT_OF_MEASURE, ATTRIBUTE_MULTIPLIER,
            ATTRIBUTE_DIVISOR, ATTRIBUTE_SUMMATION_FORMATTING};
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        if (!(attributes.get(ATTRIBUTE_CURRENT_SUMMATION_DELIVERED)
                instanceof Long raw) || !formatting.hasMetering()) {
            return List.of();
        }
        if (raw < 0 || raw >= INVALID_UINT48) {
            if (raw != INVALID_UINT48) {
                log.debug("current summation {} is outside the uint48 band; "
                        + "skipped (F-9)", raw);
            }
            return List.of();
        }
        if (!formatting.kilowattHours()) {
            log.debug("zigbee.metering_unit_unsupported: device={} endpoint={} "
                            + "unit={}; summation not scaled (only kWh 0x0 is) — "
                            + "nothing emitted", device, endpoint,
                    formatting.unitOfMeasure() == null ? "unread"
                            : "0x" + Integer.toHexString(
                                    formatting.unitOfMeasure()));
            return List.of();
        }
        double wattHours = (double) raw * formatting.summationMultiplier()
                * WATT_HOURS_PER_KILOWATT_HOUR / formatting.summationDivisor();
        return List.of(new NormalizedAttribute("energy_wh", wattHours, "Wh",
                String.valueOf(raw), formatting.summationNote()));
    }
}
