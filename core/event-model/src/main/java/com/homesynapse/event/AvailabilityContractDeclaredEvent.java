/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;
import java.util.Optional;

/**
 * The liveness policy an integration adapter applies to a device (AMD-103 R-C;
 * AVAIL-API-1): its class, report interval, silence limit, misses to dark and record
 * interval — declared at every boot and (re-)interview, whenever the contract derived
 * at an evaluation differs from the device's last declared one, and refreshed every
 * 30 days. A declaration, never an enforcement (R-D): the tracker's arms do not change;
 * the record names the arm the tracker already takes for the device.
 *
 * <p>Subject: the DEVICE. Priority NORMAL. Category {@code device_health} (R-A).
 * Schema version 1.</p>
 *
 * <p>{@code availabilityClass} is a String on the wire ({@code class} is a reserved
 * word); {@link AvailabilityClass} names the three tokens. The mains classes are
 * ASKED (probed after their silence limit) and carry a record interval; the passive
 * class is never asked and carries none.</p>
 *
 * @param availabilityClass     {@code mains-metered} (a reporting contract: the maximum
 *                              + the 60-s floor), {@code mains-floor} (the floor alone)
 *                              or {@code passive} (a non-mains power source: the declared
 *                              interval, else the 25-h window)
 * @param reportIntervalSeconds the reporting contract's maximum interval (metered), or
 *                              the declared expected report interval (passive), or
 *                              {@code null} when none is declared
 * @param silenceLimitSeconds   the silence after which the device is asked (mains) or
 *                              named dark (passive); at least 1
 * @param missesToDark          consecutive unanswered probes that name a mains device
 *                              dark; at least 1
 * @param recordIntervalSeconds the {@code probe_answered} record interval for an asked
 *                              class; {@code null} for {@code passive}
 */
@EventType(EventTypes.AVAILABILITY_CONTRACT_DECLARED)
public record AvailabilityContractDeclaredEvent(
        String availabilityClass,
        Integer reportIntervalSeconds,
        int silenceLimitSeconds,
        int missesToDark,
        Integer recordIntervalSeconds
) implements DomainEvent {

    /**
     * The three availability classes — the arms the tracker takes for a device
     * (AMD-103 R-C). The token is the wire value of {@link #availabilityClass()}.
     */
    public enum AvailabilityClass {
        /** Mains-powered with a configured reporting contract: asked past the maximum + 60 s. */
        MAINS_METERED("mains-metered", true),
        /** Mains-powered without a contract: asked past the 60-s floor. */
        MAINS_FLOOR("mains-floor", true),
        /** A non-mains power source: never asked; dark after the declared interval, else 25 h. */
        PASSIVE("passive", false);

        private final String token;
        private final boolean asked;

        AvailabilityClass(String token, boolean asked) {
            this.token = token;
            this.asked = asked;
        }

        /**
         * Returns the wire token.
         *
         * @return the token, e.g. {@code mains-metered}
         */
        public String token() {
            return token;
        }

        /**
         * Returns whether the class is probed for liveness and therefore recorded.
         *
         * @return {@code true} for the mains classes
         */
        public boolean asked() {
            return asked;
        }

        /**
         * Resolves a wire token.
         *
         * @param token the token, nullable
         * @return the class, or empty for {@code null} or an unknown token
         */
        public static Optional<AvailabilityClass> fromToken(String token) {
            for (AvailabilityClass candidate : values()) {
                if (candidate.token.equals(token)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Constructs an AvailabilityContractDeclaredEvent with validation.
     *
     * @param availabilityClass     one of the three tokens, not null
     * @param reportIntervalSeconds the report interval, nullable; at least 1 when set
     * @param silenceLimitSeconds   the silence limit, at least 1
     * @param missesToDark          the misses to dark, at least 1
     * @param recordIntervalSeconds the record interval: required for an asked class,
     *                              {@code null} for the passive class; at least 1 when set
     */
    public AvailabilityContractDeclaredEvent {
        Objects.requireNonNull(availabilityClass, "availabilityClass cannot be null");
        AvailabilityClass resolved = AvailabilityClass.fromToken(availabilityClass)
                .orElseThrow(() -> new IllegalArgumentException(
                        "availabilityClass must be one of mains-metered, mains-floor, passive; got "
                                + availabilityClass));
        if (reportIntervalSeconds != null && reportIntervalSeconds < 1) {
            throw new IllegalArgumentException(
                    "reportIntervalSeconds must be >= 1 when set, got " + reportIntervalSeconds);
        }
        if (silenceLimitSeconds < 1) {
            throw new IllegalArgumentException(
                    "silenceLimitSeconds must be >= 1, got " + silenceLimitSeconds);
        }
        if (missesToDark < 1) {
            throw new IllegalArgumentException("missesToDark must be >= 1, got " + missesToDark);
        }
        if (resolved.asked() && recordIntervalSeconds == null) {
            throw new IllegalArgumentException(
                    "recordIntervalSeconds is required for an asked class: " + availabilityClass);
        }
        if (!resolved.asked() && recordIntervalSeconds != null) {
            throw new IllegalArgumentException(
                    "recordIntervalSeconds must be null for the passive class");
        }
        if (recordIntervalSeconds != null && recordIntervalSeconds < 1) {
            throw new IllegalArgumentException(
                    "recordIntervalSeconds must be >= 1 when set, got " + recordIntervalSeconds);
        }
    }
}
