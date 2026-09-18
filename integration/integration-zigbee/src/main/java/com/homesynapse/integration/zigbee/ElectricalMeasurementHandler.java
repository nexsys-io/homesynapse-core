/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Electrical Measurement cluster (0x0B04) handler (ENERGY-READ R4):
 * {@code ActivePower} (int16) → {@code power_w}, {@code RMSVoltage} (uint16) →
 * {@code voltage_v}, {@code RMSCurrent} (uint16) → {@code current_a} — each
 * {@code raw × multiplier / divisor} under the pair the DEVICE declared about
 * that quantity ({@link MeteringFormatting}, read at adoption).
 *
 * <p><strong>Constructed WITH a formatting — never without.</strong> The
 * handler table attaches this handler only when the {@code ActivePower} pair
 * is known ({@link ClusterHandlers#forDevice}); a voltage or current whose OWN
 * pair was not read emits nothing — the power pair is never borrowed. A
 * derived value is never presented without its observation: the raw integer
 * rides {@code rawProtocolValue} and the pair that scaled it rides
 * {@code rawProtocolUnit} ({@code "mult=1 div=100"}).
 *
 * <p>The codec's int16 decode sign-extends, so a negative {@code ActivePower}
 * (export) arrives negative and the invalid sentinel (wire {@code 0x8000})
 * arrives as −32768; the uint16 sentinel is {@code 0xFFFF}. An invalid or
 * out-of-band value never observes (the F-9 discipline): honest absence.
 *
 * <p>No command path: {@code power_meter} declares no command, and the
 * inherited {@code buildCommand} throws.
 *
 * <p>Thread-safe: stateless behavior over the immutable formatting binding.
 */
final class ElectricalMeasurementHandler extends ZigbeeClusterHandler {

    // ── BENCH-VERIFY constants (ENERGY-READ) ────────────────────────────────
    // ZCL-derived (the Zigbee Cluster Library Specification, document 07-5123,
    // the Electrical Measurement chapter: the AC (single-phase) measurement
    // set 0x05xx and the AC formatting set 0x06xx; corroborated by the field's
    // cluster tables and PLUG-DOSSIER row 28) and NOT yet silicon-verified: no
    // metering plug has joined (THE ADOPTION FENCE). The first adoption is the
    // measurement (the measurement record's G4 rows). The isolated block
    // keeps a correction a one-constant edit fixing code and tests together.

    static final int CLUSTER_ID = 0x0B04;
    /** {@code RMSVoltage} (uint16): V = raw × ACVoltageMultiplier / ACVoltageDivisor. */
    static final int ATTRIBUTE_RMS_VOLTAGE = 0x0505;
    /** {@code RMSCurrent} (uint16): A = raw × ACCurrentMultiplier / ACCurrentDivisor. */
    static final int ATTRIBUTE_RMS_CURRENT = 0x0508;
    /** {@code ActivePower} (int16): W = raw × ACPowerMultiplier / ACPowerDivisor. */
    static final int ATTRIBUTE_ACTIVE_POWER = 0x050B;
    static final int ATTRIBUTE_AC_VOLTAGE_MULTIPLIER = 0x0600;
    static final int ATTRIBUTE_AC_VOLTAGE_DIVISOR = 0x0601;
    static final int ATTRIBUTE_AC_CURRENT_MULTIPLIER = 0x0602;
    static final int ATTRIBUTE_AC_CURRENT_DIVISOR = 0x0603;
    static final int ATTRIBUTE_AC_POWER_MULTIPLIER = 0x0604;
    static final int ATTRIBUTE_AC_POWER_DIVISOR = 0x0605;
    /** ZCL data type of {@code ActivePower}: int16. */
    static final int DATA_TYPE_ACTIVE_POWER = 0x29;
    /** The int16 invalid sentinel: wire 0x8000, sign-extended to −32768 by the codec. */
    static final long INVALID_ACTIVE_POWER = (short) 0x8000;
    /** The int16 ceiling: a correctly-typed {@code ActivePower} never exceeds it. */
    static final long ACTIVE_POWER_MAX = 0x7FFF;
    /** The uint16 invalid sentinel ({@code RMSVoltage} / {@code RMSCurrent}). */
    static final long INVALID_UINT16 = 0xFFFF;

    private static final Logger log =
            LoggerFactory.getLogger(ElectricalMeasurementHandler.class);

    private final MeteringFormatting formatting;

    /**
     * Binds the handler to its device and the formatting the device declared.
     *
     * @param device the device this handler instance serves, never {@code null}
     * @param clock the time source, never {@code null}
     * @param formatting the read formatting, never {@code null}
     */
    ElectricalMeasurementHandler(IEEEAddress device, Clock clock,
            MeteringFormatting formatting) {
        super(device, clock);
        this.formatting = Objects.requireNonNull(formatting, "formatting");
    }

    /**
     * The formatting attributes of the ONE read frame, the {@code ActivePower}
     * pair first (a fresh array — the constant block stays immutable).
     */
    static int[] formattingAttributes() {
        return new int[] {ATTRIBUTE_AC_POWER_MULTIPLIER,
            ATTRIBUTE_AC_POWER_DIVISOR, ATTRIBUTE_AC_VOLTAGE_MULTIPLIER,
            ATTRIBUTE_AC_VOLTAGE_DIVISOR, ATTRIBUTE_AC_CURRENT_MULTIPLIER,
            ATTRIBUTE_AC_CURRENT_DIVISOR};
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        List<NormalizedAttribute> normalized = new ArrayList<>(3);
        if (formatting.hasElectrical()
                && attributes.get(ATTRIBUTE_ACTIVE_POWER) instanceof Long raw
                && raw != INVALID_ACTIVE_POWER) {
            if (raw < -ACTIVE_POWER_MAX || raw > ACTIVE_POWER_MAX) {
                log.debug("active power {} is outside the int16 band; skipped "
                        + "(F-9)", raw);
            } else {
                normalized.add(scaled("power_w", "W", raw,
                        formatting.powerMultiplier(), formatting.powerDivisor(),
                        formatting.powerNote()));
            }
        }
        if (formatting.hasVoltage()) {
            unsigned16(attributes.get(ATTRIBUTE_RMS_VOLTAGE), "rms voltage")
                    .ifPresent(raw -> normalized.add(scaled("voltage_v", "V", raw,
                            formatting.voltageMultiplier(),
                            formatting.voltageDivisor(),
                            formatting.voltageNote())));
        }
        if (formatting.hasCurrent()) {
            unsigned16(attributes.get(ATTRIBUTE_RMS_CURRENT), "rms current")
                    .ifPresent(raw -> normalized.add(scaled("current_a", "A", raw,
                            formatting.currentMultiplier(),
                            formatting.currentDivisor(),
                            formatting.currentNote())));
        }
        return normalized;
    }

    /** A valid uint16 observation, or empty (absent, mis-typed, sentinel, out of band). */
    private static Optional<Long> unsigned16(Object value, String name) {
        if (!(value instanceof Long raw) || raw == INVALID_UINT16) {
            return Optional.empty();
        }
        if (raw < 0 || raw > INVALID_UINT16) {
            log.debug("{} {} is outside the uint16 band; skipped (F-9)", name, raw);
            return Optional.empty();
        }
        return Optional.of(raw);
    }

    /** {@code raw × multiplier / divisor} — one division, at the last step. */
    private static NormalizedAttribute scaled(String key, String unit, long raw,
            int multiplier, int divisor, String formattingNote) {
        return new NormalizedAttribute(key, (double) raw * multiplier / divisor,
                unit, String.valueOf(raw), formattingNote);
    }
}
