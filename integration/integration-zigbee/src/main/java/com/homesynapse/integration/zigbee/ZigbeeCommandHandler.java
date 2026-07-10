/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.platform.identity.EntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The zigbee {@link CommandHandler} (Doc 08 §3.10 steps 3–7, M9.4a §3.3): identity
 * join → {@code buildCommand} → APS unicast → the honest verdict. Invoked on the
 * adapter's single-threaded command executor by the routing subscriber.
 *
 * <ul>
 *   <li><strong>Identity join (F-6):</strong> entity → the adoption slice's protocol
 *       binding → the device cache. The cached network address is a HINT — when it is
 *       the unknown sentinel or the live NWK→IEEE index disagrees, the address
 *       re-resolves through the coordinator and the cache is updated (the
 *       misdirected-actuation class closed). Data absence (no binding / no record /
 *       not in the coordinator table) publishes an {@code unroutable} failure — never
 *       a throw.</li>
 *   <li><strong>The honest immediate verdict (Doc 02 §3.8 / AMD-97, INV-SA-03;
 *       DP-B6, M9.5-DURb):</strong> the fence reads the adopted entity's INSTALLED
 *       {@code CapabilityInstance.confirmation()} first — the durable per-device
 *       tuning, carrying every §3 posture downgrade. An installed-DISABLED command
 *       still dispatches (actuation is not gated), then renders
 *       {@code command_result(outcome="unconfirmed")} with the recorded reason;
 *       the ledger never tracked it, so there is no double verdict. The profile
 *       characterization is the pre-adoption/no-entity fallback — the lookup and
 *       reason mapping are protocol knowledge and live HERE (INV-CE-04).</li>
 *   <li><strong>CONFIRMABLE/BEST_EFFORT:</strong> dispatch and publish NOTHING on
 *       success — the confirmation window owns the eventual outcome (Doc 08 §3.10
 *       step 7; the router publishes failures only). An NCP rejection publishes
 *       {@code command_result(outcome="rejected")}.</li>
 *   <li><strong>Causation (N-6, BINDING):</strong> every publication chains
 *       {@code (correlationId, commandEventId)} from the dispatched envelope — never
 *       a root publish.</li>
 * </ul>
 *
 * <p>Thread-safe: invoked on the single-threaded command executor; collaborators are
 * individually thread-safe.</p>
 */
final class ZigbeeCommandHandler implements CommandHandler {

    /** Sends one ZCL frame; {@code true} = the NCP accepted it (the §3.2 seam). */
    interface ZclDispatch {
        boolean send(ZclFrame frame, int networkAddress);
    }

    /** Resolves an IEEE address to its current network address (the F-6 seam). */
    interface AddressLookup {
        int lookup(IEEEAddress device);
    }

    /** ZCL8 §3.5: the Identify cluster and its Identify command (identifyTime u16 s). */
    static final int IDENTIFY_CLUSTER_ID = 0x0003;
    static final int COMMAND_IDENTIFY = 0x00;
    private static final int DEFAULT_IDENTIFY_SECONDS = 3;

    /**
     * Commands with NO reporting surface on any device — adapter-side protocol
     * knowledge (INV-CE-04: this set never leaves the adapter). When such a
     * command dispatches and the matched profile carries no characterization for
     * it, the SD-3 fence renders the generic immediate {@code unconfirmed}
     * verdict — never silence (M9.4b §3.3).
     */
    private static final Set<String> INHERENTLY_UNCONFIRMABLE =
            Set.of("identify", "color_loop");

    /**
     * Adapter-side command → characterization-capability vocabulary (INV-CE-04:
     * this mapping is protocol knowledge and never leaves the adapter). The
     * {@code identify}/{@code effect} keys name profile characterizations that have
     * no classified core capability — their honest verdict is rendered here.
     */
    private static final Map<String, String> CAPABILITY_BY_COMMAND = Map.of(
            "turn_on", "on_off",
            "turn_off", "on_off",
            "toggle", "on_off",
            "set_brightness", "brightness",
            "set_color_temperature", "color_temperature",
            "identify", "identify",
            "color_loop", "effect");

    private static final int SCHEMA_VERSION = 1;

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeCommandHandler.class);

    private final ZigbeeAdoptionSlice adoption;
    private final EntityRegistry entityRegistry;
    private final ZigbeeDeviceCache cache;
    private final DeviceProfileRegistry profileRegistry;
    private final ZclDispatch dispatch;
    private final AddressLookup addressLookup;
    private final EventPublisher publisher;
    private final Clock clock;

    ZigbeeCommandHandler(ZigbeeAdoptionSlice adoption, EntityRegistry entityRegistry,
            ZigbeeDeviceCache cache,
            DeviceProfileRegistry profileRegistry, ZclDispatch dispatch,
            AddressLookup addressLookup, EventPublisher publisher, Clock clock) {
        this.adoption = Objects.requireNonNull(adoption, "adoption");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.addressLookup = Objects.requireNonNull(addressLookup, "addressLookup");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(CommandEnvelope command) {
        Objects.requireNonNull(command, "command");
        Optional<ZigbeeAdoptionSlice.EntityBinding> binding =
                adoption.bindingFor(command.entityRef());
        if (binding.isEmpty()) {
            publishResult(command, "unroutable", EventPriority.CRITICAL,
                    "Entity " + command.entityRef() + " has no zigbee device binding");
            return;
        }
        IEEEAddress ieee = binding.get().ieee();
        Optional<ZigbeeDeviceRecord> record = cache.device(ieee);
        if (record.isEmpty()) {
            publishResult(command, "unroutable", EventPriority.CRITICAL,
                    "Device " + ieee + " is not in the zigbee device cache");
            return;
        }

        // F-6: the cached address is a hint, never the truth — re-resolve when it is
        // the unknown sentinel or the live NWK→IEEE index disagrees.
        int networkAddress = record.get().networkAddress();
        boolean stale = networkAddress == ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN
                || cache.deviceForNetworkAddress(networkAddress)
                        .map(owner -> owner.value() != ieee.value())
                        .orElse(true);
        if (stale) {
            try {
                networkAddress = addressLookup.lookup(ieee);
            } catch (IllegalStateException notInTable) {
                publishResult(command, "unroutable", EventPriority.CRITICAL,
                        "Device " + ieee + " is not in the coordinator address table: "
                                + notInTable.getMessage());
                return;
            }
            cache.recordAnnounce(ieee, networkAddress);
            log.info("zigbee.address_refreshed: device={} nwk=0x{} — stale cache "
                            + "hint re-resolved before dispatch (F-6)",
                    ieee, Integer.toHexString(networkAddress));
        }

        ZclFrame built = buildFrame(ieee, command);
        ZclFrame frame = new ZclFrame(built.sourceEndpoint(), binding.get().endpoint(),
                built.clusterId(), built.commandId(), built.isClusterSpecific(),
                built.manufacturerCode(), built.payload());
        if (!dispatch.send(frame, networkAddress)) {
            publishResult(command, "rejected", EventPriority.CRITICAL,
                    "APS unicast rejected by the coordinator: cluster=0x"
                            + Integer.toHexString(frame.clusterId())
                            + " nwk=0x" + Integer.toHexString(networkAddress));
            return;
        }

        // The Doc 02 §3.8 "reason recorded" half — DP-B6 (M9.5-DURb): the fence
        // consults the adopted entity's INSTALLED policy FIRST. The registry-held
        // CapabilityInstance carries the DP-a adoption tuning AND every §3
        // measured-posture downgrade, both durable across restarts (AMD-99) —
        // the profile file is pre-adoption truth and can drift from what is
        // installed. Installed-DISABLED still dispatches (actuation is not
        // gated), is never ledger-tracked (AMD-97-INV-01 structural), and
        // renders the immediate honest UNCONFIRMED verdict with the recorded
        // reason — never a silent bypass, never CONFIRMED. A kept (non-DISABLED)
        // policy publishes NOTHING: the confirmation window owns the outcome on
        // the tuned timeout. The characterization remains the pre-adoption /
        // no-entity fallback; INHERENTLY_UNCONFIRMABLE backstops commands with
        // no confirmation surface anywhere (SD-3: "never-tracked and
        // honestly-verdicted are different promises").
        Optional<ConfirmationPolicy> installed =
                installedPolicyFor(command.entityRef(), command.commandName());
        if (installed.isPresent()) {
            if (installed.get().mode() == ConfirmationMode.DISABLED) {
                publishResult(command, "unconfirmed", EventPriority.NORMAL,
                        disabledReason(record.get(), ieee, command.commandName()));
            }
            return;
        }
        Optional<ConfirmationCharacterization> characterization =
                characterizationFor(record.get(), ieee, command.commandName());
        if (characterization.isPresent()) {
            characterization
                    .filter(c -> c.confirmability() == Confirmability.UNCONFIRMABLE)
                    .ifPresent(c -> publishResult(command, "unconfirmed",
                            EventPriority.NORMAL, unconfirmableReason(c)));
        } else if (INHERENTLY_UNCONFIRMABLE.contains(command.commandName())) {
            publishResult(command, "unconfirmed", EventPriority.NORMAL,
                    noSurfaceReason(command.commandName()));
        }
    }

    /**
     * Resolves the adopted entity's INSTALLED confirmation policy for a
     * command's capability (DP-B6 — the fence's first read). Empty when the
     * command maps to no capability, the entity is not in the registry
     * (pre-adoption), or the capability is not installed on it — the
     * characterization fallback covers those shapes.
     */
    private Optional<ConfirmationPolicy> installedPolicyFor(EntityId entityRef,
            String commandName) {
        String capability = CAPABILITY_BY_COMMAND.get(commandName);
        if (capability == null) {
            return Optional.empty();
        }
        return entityRegistry.findEntity(entityRef)
                .flatMap(entity -> entity.capabilities().stream()
                        .filter(instance ->
                                capability.equals(instance.capabilityId()))
                        .findFirst())
                .map(CapabilityInstance::confirmation);
    }

    /**
     * The installed-DISABLED verdict's recorded reason: the characterization's
     * measured unconfirmable note where one exists (the reason recorded when
     * the device was characterized), else the SD-3 generic for commands with
     * no confirmation surface anywhere, else the installed policy itself (the
     * §3 posture-downgrade class — the measurement lives in the downgrade WARN
     * and the posture fact).
     */
    private String disabledReason(ZigbeeDeviceRecord record, IEEEAddress ieee,
            String commandName) {
        Optional<ConfirmationCharacterization> characterization =
                characterizationFor(record, ieee, commandName)
                        .filter(c -> c.confirmability()
                                == Confirmability.UNCONFIRMABLE);
        if (characterization.isPresent()) {
            return unconfirmableReason(characterization.get());
        }
        if (INHERENTLY_UNCONFIRMABLE.contains(commandName)) {
            return noSurfaceReason(commandName);
        }
        return "the installed confirmation policy for '"
                + CAPABILITY_BY_COMMAND.get(commandName)
                + "' is DISABLED; the command was issued and is not tracked";
    }

    private static String noSurfaceReason(String commandName) {
        return "no confirmation surface exists for '" + commandName
                + "'; the command was issued and is not tracked";
    }

    /** Builds the protocol frame for a command (adapter vocabulary — INV-CE-04). */
    private ZclFrame buildFrame(IEEEAddress ieee, CommandEnvelope command) {
        return switch (command.commandName()) {
            case "turn_on", "turn_off", "toggle" ->
                    new OnOffHandler(ieee, clock)
                            .buildCommand(command.commandName(), command.parameters());
            case "set_brightness" ->
                    new LevelControlHandler(ieee, clock)
                            .buildCommand(command.commandName(), command.parameters());
            case "set_color_temperature", "color_loop" ->
                    new ColorControlHandler(ieee, clock)
                            .buildCommand(command.commandName(), command.parameters());
            // ZCL8 §3.5.2.2.1 Identify: [identifyTime u16 LE, seconds]. No classified
            // capability owns this cluster — the frame builds here, adapter-side.
            case "identify" -> {
                int seconds = ZigbeeClusterHandler.intParameter(
                        command.parameters(), "duration_s", DEFAULT_IDENTIFY_SECONDS);
                yield new ZclFrame(1, 1, IDENTIFY_CLUSTER_ID, COMMAND_IDENTIFY, true, 0,
                        new byte[] {(byte) (seconds & 0xFF),
                                (byte) ((seconds >> 8) & 0xFF)});
            }
            default -> throw new UnsupportedOperationException(
                    "the zigbee adapter does not support command '"
                            + command.commandName() + "'");
        };
    }

    /** Resolves the matched profile's characterization for a command's capability. */
    private Optional<ConfirmationCharacterization> characterizationFor(
            ZigbeeDeviceRecord record, IEEEAddress ieee, String commandName) {
        String capability = CAPABILITY_BY_COMMAND.get(commandName);
        if (capability == null) {
            return Optional.empty();
        }
        String profileId = record.matchedProfileId() != null
                ? record.matchedProfileId()
                : adoption.matchedProfileIdFor(ieee).orElse(null);
        if (profileId == null) {
            return Optional.empty();
        }
        return profileRegistry.allProfiles().stream()
                .filter(profile -> profileId.equals(profile.profileId()))
                .findFirst()
                .map(DeviceProfile::confirmation)
                .flatMap(characterizations -> characterizations == null
                        ? Optional.empty()
                        : characterizations.stream()
                                .filter(c -> capability.equals(c.capability()))
                                .findFirst());
    }

    private static String unconfirmableReason(ConfirmationCharacterization c) {
        return c.notes() != null ? c.notes()
                : "the device provides no authoritative report for '" + c.capability()
                        + "' (" + c.degradeRule() + ")";
    }

    /**
     * N-6 (BINDING): every result chains causation from the dispatched command's
     * envelope — correlation = the run's, causation = the {@code command_issued} id.
     */
    private void publishResult(CommandEnvelope command, String outcome,
            EventPriority priority, String failureReason) {
        EventDraft draft = new EventDraft(EventTypes.COMMAND_RESULT, SCHEMA_VERSION,
                null, SubjectRef.entity(command.entityRef()), priority,
                EventOrigin.INTEGRATION,
                new CommandResultEvent(command.entityRef().value(),
                        command.commandName(), outcome, failureReason),
                null, null);
        try {
            publisher.publish(draft, CausalContext.chain(
                    command.correlationId(), command.commandEventId()));
        } catch (SequenceConflictException conflict) {
            log.error("zigbee.command_result_conflict: outcome={} entity={}: {}",
                    outcome, command.entityRef(), conflict.getMessage());
        }
        log.info("zigbee.command_result: outcome={} entity={} command={} "
                        + "correlation={} reason={}", outcome, command.entityRef(),
                command.commandName(), command.correlationId(), failureReason);
    }
}
