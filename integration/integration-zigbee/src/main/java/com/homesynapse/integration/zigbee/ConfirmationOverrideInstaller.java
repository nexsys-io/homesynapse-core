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
 */
final class ConfirmationOverrideInstaller {

    private static final Logger log =
            LoggerFactory.getLogger(ConfirmationOverrideInstaller.class);

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
}
