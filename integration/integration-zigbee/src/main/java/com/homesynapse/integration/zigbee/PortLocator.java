/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Locates the coordinator serial port by USB VID:PID with by-id-path preference —
 * NEVER by USB descriptor strings (AMD-96/E2, INV-CE-04: the bench unit reports
 * SONOFF-branded strings, not {@code Silicon_Labs_CP2102N}; descriptor matching would
 * have failed on real Wave-1 silicon).
 *
 * <p>Reopen support targets the STABLE identity: a re-enumerated (renumbered) device
 * node is re-found by its by-id path first, then by VID:PID class match — never by
 * the possibly-stale system path alone (W5 neighborhood; consumed by
 * {@link PortWatchdog}'s reopen action at M9.4 wiring).
 *
 * <p>Thread-safe: stateless over an injected enumerator.
 *
 * @see PortCandidate
 * @see PortIdentity
 */
final class PortLocator {

    /** The CP210x-bridged SONOFF MG21/MG24 coordinator class (bench corpus A14). */
    static final int VENDOR_SILICON_LABS_CP210X = 0x10C4;
    /** The CP210x-bridged SONOFF MG21/MG24 coordinator class (bench corpus A14). */
    static final int PRODUCT_CP210X_UART_BRIDGE = 0xEA60;

    private static final Logger log = LoggerFactory.getLogger(PortLocator.class);

    /**
     * Enumeration seam: supplies the visible serial ports. Production binding is
     * {@link JSerialCommPortEnumerator}; tests inject fixed candidate lists.
     */
    interface PortEnumerator {

        /**
         * Enumerates the currently visible serial ports.
         *
         * @return the candidates, never {@code null}
         */
        List<PortCandidate> enumerate();
    }

    private final PortEnumerator enumerator;

    /**
     * Creates a locator over {@code enumerator}. Performs no I/O (INV-RF-03).
     *
     * @param enumerator the enumeration seam, never {@code null}
     */
    PortLocator(PortEnumerator enumerator) {
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator");
    }

    /**
     * Locates a coordinator-class port: filters to known coordinator VID:PID pairs,
     * prefers candidates with a stable by-id path, and orders deterministically.
     * Descriptor strings never participate.
     *
     * @return the best candidate, or empty when none matches
     */
    Optional<PortCandidate> locate() {
        List<PortCandidate> matches = enumerator.enumerate().stream()
                .filter(PortLocator::isKnownCoordinatorBridge)
                .sorted(byStability())
                .toList();
        if (matches.size() > 1) {
            log.warn("zigbee.multiple_coordinator_ports: {} candidates match the "
                            + "coordinator USB class; selecting {}",
                    matches.size(), matches.get(0).stablePath());
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * Re-finds a previously identified port after unplug/renumbering: first by the
     * stable id (by-id path or recorded stable path), then by VID:PID class match —
     * the device node may have renumbered ({@code ttyUSB0} → {@code ttyUSB1}).
     *
     * @param identity the previously captured identity, never {@code null}
     * @return the candidate to reopen, or empty when the device is absent
     */
    Optional<PortCandidate> reopenTarget(PortIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        List<PortCandidate> candidates = enumerator.enumerate();
        Optional<PortCandidate> byStableId = candidates.stream()
                .filter(c -> identity.stableId().equals(c.byIdPath())
                        || identity.stableId().equals(c.systemPath()))
                .findFirst();
        if (byStableId.isPresent()) {
            return byStableId;
        }
        return candidates.stream()
                .filter(c -> c.vendorId() == identity.vendorId()
                        && c.productId() == identity.productId())
                .sorted(byStability())
                .findFirst();
    }

    /**
     * Builds the identity record for a located candidate.
     *
     * @param candidate the located port, never {@code null}
     * @param probeFingerprint the negotiated stack version, or {@code null} before
     *                         first contact
     * @return the identity (stable path preferred over system path)
     */
    static PortIdentity identityFor(PortCandidate candidate, String probeFingerprint) {
        Objects.requireNonNull(candidate, "candidate");
        return new PortIdentity(candidate.vendorId(), candidate.productId(),
                candidate.stablePath(), probeFingerprint);
    }

    private static boolean isKnownCoordinatorBridge(PortCandidate candidate) {
        return candidate.vendorId() == VENDOR_SILICON_LABS_CP210X
                && candidate.productId() == PRODUCT_CP210X_UART_BRIDGE;
    }

    /** By-id-bearing candidates first, then lexicographic stable path. */
    private static Comparator<PortCandidate> byStability() {
        return Comparator
                .comparing((PortCandidate c) -> c.byIdPath() == null)
                .thenComparing(PortCandidate::stablePath);
    }
}
