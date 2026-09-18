/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * The metering formatting one endpoint DECLARED about itself (ENERGY-READ R3):
 * the Electrical Measurement (0x0B04) AC multiplier/divisor pairs and the
 * Simple Metering (0x0702) multiplier/divisor/unit, as READ from the device at
 * adoption — never predicted, never defaulted, never overridden.
 *
 * <p><strong>Presence is per pair.</strong> A pair is PRESENT only when both
 * its multiplier and its divisor were read as non-zero values; a missing
 * response, a failed record (the codec drops {@code UNSUPPORTED_ATTRIBUTE}
 * records) or a zero collapses the WHOLE pair to absent — the compact
 * constructor normalizes, so two instances describing the same knowledge are
 * equal. An absent pair scales nothing and configures nothing (honest silence
 * over a guessed scale): the ZCL default of 1/1 is a prediction about a device
 * that did not answer, and this type never makes it.
 *
 * <p>{@code unitOfMeasure} is carried as read ({@code null} = unread); only
 * {@link #UNIT_KILOWATT_HOURS} is ever scaled — see {@link #kilowattHours()}.
 *
 * @param powerMultiplier {@code ACPowerMultiplier}; {@code 0} = absent
 * @param powerDivisor {@code ACPowerDivisor}; {@code 0} = absent
 * @param voltageMultiplier {@code ACVoltageMultiplier}; {@code 0} = absent
 * @param voltageDivisor {@code ACVoltageDivisor}; {@code 0} = absent
 * @param currentMultiplier {@code ACCurrentMultiplier}; {@code 0} = absent
 * @param currentDivisor {@code ACCurrentDivisor}; {@code 0} = absent
 * @param summationMultiplier the 0x0702 {@code Multiplier}; {@code null} = absent
 * @param summationDivisor the 0x0702 {@code Divisor}; {@code null} = absent
 * @param unitOfMeasure the 0x0702 {@code UnitOfMeasure} as read; {@code null}
 *        when unread
 */
record MeteringFormatting(int powerMultiplier, int powerDivisor,
        int voltageMultiplier, int voltageDivisor,
        int currentMultiplier, int currentDivisor,
        Integer summationMultiplier, Integer summationDivisor,
        Integer unitOfMeasure) {

    /** ZCL Simple Metering {@code UnitOfMeasure} 0x00: kWh, binary-coded. */
    static final int UNIT_KILOWATT_HOURS = 0x00;

    /** The 0x0B04 AC multiplier/divisor wire type: uint16. */
    private static final long UINT16_MAX = 0xFFFFL;
    /** The 0x0702 Multiplier/Divisor wire type: uint24. */
    private static final long UINT24_MAX = 0xFF_FFFFL;

    private static final MeteringFormatting UNKNOWN = new MeteringFormatting(
            0, 0, 0, 0, 0, 0, null, null, null);

    /**
     * Normalizes presence: a pair with a non-positive or missing half is
     * absent as a whole.
     */
    MeteringFormatting {
        if (powerMultiplier <= 0 || powerDivisor <= 0) {
            powerMultiplier = 0;
            powerDivisor = 0;
        }
        if (voltageMultiplier <= 0 || voltageDivisor <= 0) {
            voltageMultiplier = 0;
            voltageDivisor = 0;
        }
        if (currentMultiplier <= 0 || currentDivisor <= 0) {
            currentMultiplier = 0;
            currentDivisor = 0;
        }
        if (summationMultiplier == null || summationDivisor == null
                || summationMultiplier <= 0 || summationDivisor <= 0) {
            summationMultiplier = null;
            summationDivisor = null;
        }
    }

    /** The formatting of an endpoint that declared nothing readable. */
    static MeteringFormatting unknown() {
        return UNKNOWN;
    }

    /**
     * Builds the formatting from the two decoded Read Attributes responses
     * (the {@link ZclCodec#parseReadAttributesResponse} maps).
     *
     * @param electrical the decoded 0x0B04 formatting read; {@code null} when
     *        the cluster was not read or did not answer
     * @param metering the decoded 0x0702 formatting read; {@code null} when
     *        the cluster was not read or did not answer
     * @return the formatting; {@link #unknown()} when nothing was readable
     */
    static MeteringFormatting ofReads(Map<Integer, Object> electrical,
            Map<Integer, Object> metering) {
        return new MeteringFormatting(
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_POWER_MULTIPLIER,
                        UINT16_MAX),
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_POWER_DIVISOR,
                        UINT16_MAX),
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_VOLTAGE_MULTIPLIER,
                        UINT16_MAX),
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_VOLTAGE_DIVISOR,
                        UINT16_MAX),
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_CURRENT_MULTIPLIER,
                        UINT16_MAX),
                uint(electrical,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_CURRENT_DIVISOR,
                        UINT16_MAX),
                uint(metering, MeteringHandler.ATTRIBUTE_MULTIPLIER, UINT24_MAX),
                uint(metering, MeteringHandler.ATTRIBUTE_DIVISOR, UINT24_MAX),
                enum8(metering, MeteringHandler.ATTRIBUTE_UNIT_OF_MEASURE));
    }

    /**
     * One decoded unsigned attribute within its wire type's range, else
     * {@code 0} (absent): a missing response, a failed record, a value of the
     * wrong Java type or one outside the type is UNREAD — never truncated.
     */
    private static int uint(Map<Integer, Object> read, int attributeId, long max) {
        if (read != null && read.get(attributeId) instanceof Long value
                && value > 0 && value <= max) {
            return value.intValue();
        }
        return 0;
    }

    /** The decoded enum8, or {@code null} when unread or out of the type. */
    private static Integer enum8(Map<Integer, Object> read, int attributeId) {
        if (read != null && read.get(attributeId) instanceof Long value
                && value >= 0 && value <= 0xFF) {
            return value.intValue();
        }
        return null;
    }

    /** True when the {@code ActivePower} pair was read — the 0x0B04 gate. */
    boolean hasElectrical() {
        return powerMultiplier > 0;
    }

    /** True when the {@code RMSVoltage} pair was read. */
    boolean hasVoltage() {
        return voltageMultiplier > 0;
    }

    /** True when the {@code RMSCurrent} pair was read. */
    boolean hasCurrent() {
        return currentMultiplier > 0;
    }

    /** True when the summation pair was read — the 0x0702 gate. */
    boolean hasMetering() {
        return summationMultiplier != null;
    }

    /**
     * True when the summation is scalable to Wh: the pair was read AND the
     * device declared kWh. Any other unit — or an unread one — is never
     * guessed at.
     */
    boolean kilowattHours() {
        return hasMetering() && Objects.equals(unitOfMeasure,
                UNIT_KILOWATT_HOURS);
    }

    /**
     * This formatting with every pair it lacks filled from {@code other} —
     * the rejoin arm's per-cluster merge of cached and freshly read knowledge.
     * A pair present here is never replaced.
     *
     * @param other the formatting to fill from, never {@code null}
     * @return the merged formatting
     */
    MeteringFormatting filledFrom(MeteringFormatting other) {
        Objects.requireNonNull(other, "other");
        boolean ownSummation = hasMetering();
        return new MeteringFormatting(
                hasElectrical() ? powerMultiplier : other.powerMultiplier,
                hasElectrical() ? powerDivisor : other.powerDivisor,
                hasVoltage() ? voltageMultiplier : other.voltageMultiplier,
                hasVoltage() ? voltageDivisor : other.voltageDivisor,
                hasCurrent() ? currentMultiplier : other.currentMultiplier,
                hasCurrent() ? currentDivisor : other.currentDivisor,
                ownSummation ? summationMultiplier : other.summationMultiplier,
                ownSummation ? summationDivisor : other.summationDivisor,
                // The unit travels with its pair: a unit read beside an absent
                // pair says nothing about the pair that fills it.
                ownSummation ? unitOfMeasure : other.unitOfMeasure);
    }

    /**
     * The knowledge a device's endpoints AGREE on — the per-device reduction
     * the ingestion's handler table needs (one handler per cluster per
     * device). Per pair, across ALL the endpoints at once: a pair is present
     * iff every endpoint that holds it holds the SAME values; a pair two
     * endpoints hold differently is absent, whatever a third one says. A scale
     * is never borrowed from an endpoint that disagrees.
     *
     * @param endpoints the device's per-endpoint formattings, never {@code null}
     * @return the agreed formatting; {@link #unknown()} for no endpoints
     */
    static MeteringFormatting agreedAcross(
            Collection<MeteringFormatting> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        MeteringFormatting power = null;
        MeteringFormatting voltage = null;
        MeteringFormatting current = null;
        MeteringFormatting summation = null;
        boolean powerSplit = false;
        boolean voltageSplit = false;
        boolean currentSplit = false;
        boolean summationSplit = false;
        for (MeteringFormatting endpoint : endpoints) {
            if (endpoint.hasElectrical()) {
                powerSplit |= power != null && (power.powerMultiplier
                        != endpoint.powerMultiplier
                        || power.powerDivisor != endpoint.powerDivisor);
                power = endpoint;
            }
            if (endpoint.hasVoltage()) {
                voltageSplit |= voltage != null && (voltage.voltageMultiplier
                        != endpoint.voltageMultiplier
                        || voltage.voltageDivisor != endpoint.voltageDivisor);
                voltage = endpoint;
            }
            if (endpoint.hasCurrent()) {
                currentSplit |= current != null && (current.currentMultiplier
                        != endpoint.currentMultiplier
                        || current.currentDivisor != endpoint.currentDivisor);
                current = endpoint;
            }
            if (endpoint.hasMetering()) {
                summationSplit |= summation != null && !(summation
                        .summationMultiplier.equals(endpoint.summationMultiplier)
                        && summation.summationDivisor
                                .equals(endpoint.summationDivisor)
                        && Objects.equals(summation.unitOfMeasure,
                                endpoint.unitOfMeasure));
                summation = endpoint;
            }
        }
        boolean keepPower = power != null && !powerSplit;
        boolean keepVoltage = voltage != null && !voltageSplit;
        boolean keepCurrent = current != null && !currentSplit;
        boolean keepSummation = summation != null && !summationSplit;
        return new MeteringFormatting(
                keepPower ? power.powerMultiplier : 0,
                keepPower ? power.powerDivisor : 0,
                keepVoltage ? voltage.voltageMultiplier : 0,
                keepVoltage ? voltage.voltageDivisor : 0,
                keepCurrent ? current.currentMultiplier : 0,
                keepCurrent ? current.currentDivisor : 0,
                keepSummation ? summation.summationMultiplier : null,
                keepSummation ? summation.summationDivisor : null,
                keepSummation ? summation.unitOfMeasure : null);
    }

    /** The {@code ActivePower} pair as the record renders it: {@code mult=1 div=100}. */
    String powerNote() {
        return pairNote(powerMultiplier, powerDivisor);
    }

    /** The {@code RMSVoltage} pair as the record renders it. */
    String voltageNote() {
        return pairNote(voltageMultiplier, voltageDivisor);
    }

    /** The {@code RMSCurrent} pair as the record renders it. */
    String currentNote() {
        return pairNote(currentMultiplier, currentDivisor);
    }

    /**
     * The summation pair and the unit as read:
     * {@code mult=1 div=1000000 unit=0x0} ({@code unit=unread} when the device
     * never declared one).
     */
    String summationNote() {
        return pairNote(summationMultiplier, summationDivisor) + " unit="
                + (unitOfMeasure == null ? "unread"
                        : "0x" + Integer.toHexString(unitOfMeasure));
    }

    /**
     * The 0x0B04 cluster's whole declaration for the journal:
     * {@code mult=1 div=100 voltage=1/10 current=1/1000} (an unread V/I pair
     * prints {@code unread}).
     */
    String electricalNote() {
        return powerNote()
                + " voltage=" + (hasVoltage()
                        ? voltageMultiplier + "/" + voltageDivisor : "unread")
                + " current=" + (hasCurrent()
                        ? currentMultiplier + "/" + currentDivisor : "unread");
    }

    private static String pairNote(Integer multiplier, Integer divisor) {
        return "mult=" + multiplier + " div=" + divisor;
    }
}
