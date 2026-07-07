/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The DP-a realization (Doc 02 §3.8 / AMD-97, M9.4a): maps a matched profile's measured
 * {@code confirmation[]} characterizations onto the per-entity {@link CapabilityInstance}
 * list ONCE, at adoption (and again on re-link — DP-a pin 2). This class is the pin-1 home:
 * <em>"the mapping lives adapter-side (INV-CE-04 — core never learns Zigbee vocabulary)"</em>
 * — every profile/characterization term stays here; downstream, the ledger's and executor's
 * existing read paths ({@code ConfirmationPolicy} mode/tolerance/timeout,
 * {@code CommandDefinition.defaultTimeout}) work unchanged and protocol-agnostically.
 *
 * <p>Mapping, per {@link Confirmability}:</p>
 * <ul>
 *   <li><strong>CONFIRMABLE / BEST_EFFORT</strong> — the capability's mode and
 *       authoritative attributes are KEPT (the characterization tunes, never re-modes);
 *       {@link ConfirmationPolicy#defaultTimeoutMs()} becomes the characterization's
 *       measured {@code recommendedTimeoutMs}, and every {@link CommandDefinition} is
 *       rebuilt with {@code defaultTimeout = Duration.ofMillis(recommendedTimeoutMs)} so
 *       the executor's capability-precedence carries the per-device window into
 *       {@code command_issued.confirmationTimeoutMs} — zero executor change. A
 *       non-positive recommended timeout leaves timeouts untouched (no zero-window).</li>
 *   <li><strong>UNCONFIRMABLE</strong> — {@code ConfirmationPolicy(DISABLED, [], null,
 *       <capability default timeoutMs>)}: the ledger's DISABLED bypass then guarantees
 *       never-tracked &rArr; never-CONFIRMED structurally (AMD-97-INV-01); the
 *       "reason recorded" half is the command handler's immediate honest verdict (the
 *       adapter owns the reason — it is protocol knowledge).</li>
 *   <li>Capabilities without a characterization: UNTOUCHED (standard defaults).
 *       Characterization ids naming no classified capability: skipped with one WARN
 *       naming the profile (tolerate-unknown — the measured posture may describe
 *       protocol surfaces the classifier does not map, e.g. identify).</li>
 * </ul>
 *
 * <p>Records are immutable — instances are reconstructed with untouched components
 * copied verbatim; {@code featureMap} stays as built (0 — the N-10 note).</p>
 *
 * <p><strong>M9.4-RPT §3 — the measured-posture routing:</strong>
 * {@link #applyPostureFacts} consumes the reporting drive's
 * {@link ReportingPostureFact} rows and enforces never-false-CONFIRMED at
 * INSTALL time (AMD-97-INV-01): a capability whose confirmation rides
 * authoritative reports stays fully report-confirmable only where the covering
 * cluster's posture is read-back VERIFIED. The ratified AMD-95/AMD-97 mapping
 * (Doc 08 §3.6): a {@code NONE}-class fact (the attribute is absent) is the
 * UNCONFIRMABLE class → {@code ConfirmationMode.DISABLED} with the reason
 * recorded (the WARN + the fact — never silent optimism); every other
 * non-verified fact (readback-only, sleepy timeout, ACK-lies, no-configure
 * skip) is the BEST_EFFORT class → the tracking machinery is KEPT (a
 * {@code CONFIRMED} only ever renders on genuine report evidence — Register
 * §51) and the re-rating is ONE loud WARN naming device + cluster + posture.
 * Never-tracked and honestly-verdicted remain different promises.</p>
 */
final class ConfirmationOverrideInstaller {

    private static final Logger log =
            LoggerFactory.getLogger(ConfirmationOverrideInstaller.class);

    /**
     * The covering cluster per report-confirmable capability (adapter-side
     * protocol knowledge, INV-CE-04): the cluster whose reporting posture
     * governs whether the capability's authoritative reports actually flow.
     * Mirrors the {@code EndpointClassifier} attachment sources; a capability
     * absent here (a future vocabulary row) is left untouched by the routing —
     * grow this table with the classifier's.
     */
    private static final Map<String, Integer> COVERING_CLUSTER_BY_CAPABILITY =
            Map.of(
                    "on_off", OnOffHandler.CLUSTER_ID,
                    "brightness", LevelControlHandler.CLUSTER_ID,
                    "color_temperature", ColorControlHandler.CLUSTER_ID,
                    "occupancy", OccupancySensingHandler.CLUSTER_ID,
                    "battery", PowerConfigurationHandler.CLUSTER_ID,
                    "motion", IasZoneHandler.CLUSTER_ID);

    private ConfirmationOverrideInstaller() {
        // Static mapping table — no instantiation.
    }

    /**
     * Applies the profile's per-capability confirmation tuning.
     *
     * @param profile the matched device profile, never {@code null}
     * @param capabilities the freshly classified capability instances, never {@code null}
     * @return the list with per-device tuning applied; the SAME data when the profile
     *         carries no characterizations
     */
    static List<CapabilityInstance> apply(DeviceProfile profile,
            List<CapabilityInstance> capabilities) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(capabilities, "capabilities");
        List<ConfirmationCharacterization> characterizations = profile.confirmation();
        if (characterizations == null || characterizations.isEmpty()) {
            return capabilities;    // read-only / uncharacterized device — standard defaults
        }
        Map<String, ConfirmationCharacterization> byCapability = new HashMap<>();
        for (ConfirmationCharacterization characterization : characterizations) {
            byCapability.put(characterization.capability(), characterization);
        }
        List<CapabilityInstance> tuned = new ArrayList<>(capabilities.size());
        for (CapabilityInstance instance : capabilities) {
            ConfirmationCharacterization characterization =
                    byCapability.remove(instance.capabilityId());
            tuned.add(characterization == null ? instance
                    : tune(instance, characterization));
        }
        if (!byCapability.isEmpty()) {
            log.warn("zigbee.characterization_unmatched: profile={} names capabilities "
                            + "the classifier did not map: {} — skipped (tolerate-unknown)",
                    profile.profileId(), byCapability.keySet());
        }
        return List.copyOf(tuned);
    }

    private static CapabilityInstance tune(CapabilityInstance instance,
            ConfirmationCharacterization characterization) {
        ConfirmationPolicy policy = instance.confirmation();
        if (characterization.confirmability() == Confirmability.UNCONFIRMABLE) {
            // AMD-97-INV-01: DISABLED + empty authoritative list — never tracked,
            // never state_confirmed; command timeouts stay as built (nothing waits).
            return withPolicy(instance, instance.commands(), new ConfirmationPolicy(
                    ConfirmationMode.DISABLED, List.of(), null,
                    policy.defaultTimeoutMs()));
        }
        // CONFIRMABLE and BEST_EFFORT tune identically: best-effort-ness is expressed
        // by the measured timeout + the recorded posture, not a new mode.
        long timeoutMs = characterization.recommendedTimeoutMs();
        if (timeoutMs <= 0) {
            return instance;    // no report-wait window measured — keep standard defaults
        }
        Map<String, CommandDefinition> commands =
                new LinkedHashMap<>(instance.commands().size());
        for (Map.Entry<String, CommandDefinition> entry : instance.commands().entrySet()) {
            CommandDefinition definition = entry.getValue();
            commands.put(entry.getKey(), new CommandDefinition(
                    definition.commandType(), definition.parameters(),
                    definition.requiredFeatures(), definition.expectedOutcomes(),
                    Duration.ofMillis(timeoutMs), definition.idempotencyClass()));
        }
        return withPolicy(instance, commands, new ConfirmationPolicy(
                policy.mode(), policy.authoritativeAttributes(),
                policy.defaultTolerance(), timeoutMs));
    }

    private static CapabilityInstance withPolicy(CapabilityInstance instance,
            Map<String, CommandDefinition> commands, ConfirmationPolicy policy) {
        // featureMap stays as built (0 — the N-10 note; feature negotiation is not
        // part of the confirmation characterization).
        return new CapabilityInstance(instance.capabilityId(), instance.version(),
                instance.namespace(), instance.featureMap(), instance.attributes(),
                commands, policy);
    }

    // ── M9.4-RPT §3: the measured-posture routing ────────────────────────────

    /**
     * Routes one entity's measured posture facts into its installed
     * confirmation surface (the install-time never-false-CONFIRMED fence).
     *
     * @param device the driven device (WARN attribution), never {@code null}
     * @param endpoint the entity's protocol endpoint (facts are per-endpoint)
     * @param facts the drive's recorded posture facts, never {@code null}
     * @param capabilities the entity's installed capability instances, never
     *        {@code null}
     * @return the routed list; the SAME data when nothing downgrades
     */
    static List<CapabilityInstance> applyPostureFacts(IEEEAddress device,
            int endpoint, List<ReportingPostureFact> facts,
            List<CapabilityInstance> capabilities) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(capabilities, "capabilities");
        List<CapabilityInstance> routed = new ArrayList<>(capabilities.size());
        for (CapabilityInstance instance : capabilities) {
            routed.add(routeOne(device, endpoint, facts, instance));
        }
        return List.copyOf(routed);
    }

    /**
     * True for the strict read-back-VERIFIED fact class — the ONLY rung the
     * configurator mints unremarkable ({@code note == null}): configure
     * accepted AND the read-back matched. Every noted fact (sleepy timeout,
     * readback-only, ACK-lies, skip) is measurement-degraded, whatever its
     * {@code reportsAuthoritative} label (the M9.4-RPT §3 "VERIFIED" reading).
     */
    static boolean verifiedFact(ReportingPostureFact fact) {
        return fact.reportsAuthoritative() == ReportsAuthoritative.VERIFIED_REPORTS
                && fact.note() == null;
    }

    private static CapabilityInstance routeOne(IEEEAddress device, int endpoint,
            List<ReportingPostureFact> facts, CapabilityInstance instance) {
        ConfirmationPolicy policy = instance.confirmation();
        if (policy.mode() == ConfirmationMode.DISABLED
                || policy.authoritativeAttributes().isEmpty()) {
            return instance;    // nothing rides reports — nothing to fence
        }
        Integer coveringCluster =
                COVERING_CLUSTER_BY_CAPABILITY.get(instance.capabilityId());
        if (coveringCluster == null) {
            return instance;    // outside the routed vocabulary — untouched
        }
        ReportingPostureFact fact = facts.stream()
                .filter(f -> f.endpoint() == endpoint
                        && f.clusterId() == coveringCluster)
                .findFirst()
                .orElse(null);
        if (fact == null || verifiedFact(fact)) {
            return instance;    // unmeasured cluster row, or verified — stands
        }
        if (fact.reportsAuthoritative() == ReportsAuthoritative.NONE) {
            // The UNCONFIRMABLE class: the authoritative attribute is absent —
            // tracking would be structural noise; DISABLED with the reason
            // recorded (this WARN + the fact), the AMD-97-INV-01 fence.
            log.warn("zigbee.confirmation_downgraded: device={} capability={} "
                            + "cluster=0x{} posture={}/{} outcome=disabled",
                    device, instance.capabilityId(),
                    Integer.toHexString(fact.clusterId()),
                    fact.reportsAuthoritative(), fact.reportingPosture());
            return withPolicy(instance, instance.commands(),
                    new ConfirmationPolicy(ConfirmationMode.DISABLED, List.of(),
                            null, policy.defaultTimeoutMs()));
        }
        // The BEST_EFFORT class (readback-only / sleepy / ACK-lies / skip): the
        // tracking machinery stays — CONFIRMED only ever renders on genuine
        // report evidence, and an unanswered window renders the honest
        // UNCONFIRMED — but the re-rating is loud.
        log.warn("zigbee.confirmation_downgraded: device={} capability={} "
                        + "cluster=0x{} posture={}/{} outcome=best_effort",
                device, instance.capabilityId(),
                Integer.toHexString(fact.clusterId()),
                fact.reportsAuthoritative(), fact.reportingPosture());
        return instance;
    }
}
