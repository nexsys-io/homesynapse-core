/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Entity;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZigbeeCommandHandler} — the §3.3 unit matrix: the identity join with the
 * F-6 stale-address re-resolution, the UNCONFIRMABLE immediate honest verdict
 * (AMD-97 / INV-SA-03) with N-6 causation chaining, the unicast-reject failure
 * surface, and the unroutable data-absence results (never a throw).
 */
@DisplayName("ZigbeeCommandHandler — identity join → buildCommand → dispatch → honest verdict (M9.4a §3.3)")
class ZigbeeCommandHandlerTest {

    private static final IEEEAddress HUE = new IEEEAddress(0x0017880109AB12CDL);
    private static final int HUE_NWK = 0x260F;
    private static final int FRESH_NWK = 0x9ABC;

    /** One dispatched frame, as the fake NCP seam saw it. */
    private record Sent(ZclFrame frame, int networkAddress) {
    }

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ZigbeeAdoptionSlice slice;
    private InMemoryEntityRegistry entityRegistry;
    private ZigbeeDeviceCache cache;
    private StandardDeviceProfileRegistry profileRegistry;
    private List<Sent> sent;
    private List<IEEEAddress> lookups;
    private boolean dispatchAccepted;
    private ZigbeeCommandHandler handler;
    private EntityId hueEntity;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        profileRegistry = new StandardDeviceProfileRegistry();
        profileRegistry.register(new ZigbeeProfileLoader().loadBundled());
        InMemoryDeviceRegistry deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        slice = new ZigbeeAdoptionSlice(
                new IntegrationId(UlidFactory.generate(clock)),
                deviceRegistry, entityRegistry,
                new RegistryProjection(deviceRegistry, entityRegistry),
                profileRegistry, publisher, clock);
        cache = new ZigbeeDeviceCache(tempDir.resolve("zigbee-devices.json"), clock);
        sent = new ArrayList<>();
        lookups = new ArrayList<>();
        dispatchAccepted = true;
        handler = new ZigbeeCommandHandler(slice, entityRegistry, cache,
                profileRegistry,
                (frame, networkAddress) -> {
                    sent.add(new Sent(frame, networkAddress));
                    return dispatchAccepted;
                },
                ieee -> {
                    lookups.add(ieee);
                    return FRESH_NWK;
                },
                publisher, clock);

        InterviewResult hue = hueInterview();
        cache.recordInterview(hue, MeasuredCorpusValues.HUE_PROFILE_ID);
        slice.onDeviceDiscovered(hue, MeasuredCorpusValues.HUE_PROFILE_ID);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);
        hueEntity = adopted.entityIds().get(MeasuredCorpusValues.HUE_ENDPOINT);
    }

    private static InterviewResult hueInterview() {
        return new InterviewResult(HUE, HUE_NWK,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(MeasuredCorpusValues.HUE_ENDPOINT,
                        0x0104, 0x010D,
                        List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008,
                                0x0300, 0x1000, 0xFC01, 0xFC04),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "LCA017", 1, InterviewStatus.COMPLETE);
    }

    private CommandEnvelope envelope(EntityId entity, String command,
            Map<String, Object> parameters) {
        return new CommandEnvelope(entity, command, parameters,
                UlidFactory.generate(clock), UlidFactory.generate(clock),
                new IntegrationId(UlidFactory.generate(clock)));
    }

    private List<CommandResultEvent> results() {
        return publisher.ofType(EventTypes.COMMAND_RESULT)
                .map(e -> (CommandResultEvent) e.payload())
                .toList();
    }

    @Test
    @DisplayName("happy path turn_on: the frame reaches the dispatch seam on the right endpoint/address — ZERO publications")
    void happyPath_turnOn_dispatchesSilently() {
        handler.handle(envelope(hueEntity, "turn_on", Map.of()));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).frame().clusterId()).isEqualTo(0x0006);
        assertThat(sent.get(0).frame().commandId()).isEqualTo(0x01);
        assertThat(sent.get(0).frame().destinationEndpoint())
                .isEqualTo(MeasuredCorpusValues.HUE_ENDPOINT);
        assertThat(sent.get(0).networkAddress()).isEqualTo(HUE_NWK);
        assertThat(publisher.published())
                .as("the confirmation window owns the outcome — no fabricated result")
                .filteredOn(e -> e.eventType().equals(EventTypes.COMMAND_RESULT))
                .isEmpty();
        assertThat(lookups).as("a healthy cache hint needs no re-resolution").isEmpty();
    }

    @Test
    @DisplayName("UNCONFIRMABLE identify: dispatched AND an immediate command_result(unconfirmed) with the profile's recorded reason, causation chained (N-6)")
    void unconfirmable_identify_dispatchesAndRendersHonestVerdict() {
        CommandEnvelope command = envelope(hueEntity, "identify", Map.of());

        handler.handle(command);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).frame().clusterId())
                .isEqualTo(ZigbeeCommandHandler.IDENTIFY_CLUSTER_ID);
        assertThat(sent.get(0).frame().commandId())
                .isEqualTo(ZigbeeCommandHandler.COMMAND_IDENTIFY);

        List<EventEnvelope> published = publisher.ofType(EventTypes.COMMAND_RESULT).toList();
        assertThat(published).hasSize(1);
        CommandResultEvent result = (CommandResultEvent) published.get(0).payload();
        assertThat(result.outcome()).isEqualTo("unconfirmed");
        assertThat(result.failureReason())
                .as("the reason is the bundled profile's measured note")
                .contains("no report");
        // N-6: never a root publish — correlation is the run's, causation the command's.
        Ulid causation = published.get(0).causalContext().causationId();
        assertThat(causation).isEqualTo(command.commandEventId());
        assertThat(published.get(0).causalContext().correlationId())
                .isEqualTo(command.correlationId());
    }

    @Test
    @DisplayName("SD-3 regression fence: identify with NO profile characterization renders "
            + "EXACTLY ONE command_result(unconfirmed) with the generic recorded reason — "
            + "never silence")
    void uncharacterizedIdentify_neverSilent() {
        // A second Hue adopted WITHOUT a profile match: matchedProfileId is null
        // everywhere, so characterizationFor() is empty — the pre-M9.4b path
        // dispatched and then fell SILENT (the fence gap). SD-3 (v18 beat 5,
        // verbatim-pinned): "an issuable command whose policy is DISABLED but
        // whose characterization is absent must NOT silently bypass."
        IEEEAddress bare = new IEEEAddress(0x0017880109AB99EEL);
        InterviewResult interview = new InterviewResult(bare, 0x1234,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(MeasuredCorpusValues.HUE_ENDPOINT,
                        0x0104, 0x010D,
                        List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008,
                                0x0300, 0x1000, 0xFC01, 0xFC04),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "LCA017", 1, InterviewStatus.COMPLETE);
        cache.recordInterview(interview, null);
        slice.onDeviceDiscovered(interview, null);
        EntityId bareEntity = slice.adopt(bare).entityIds()
                .get(MeasuredCorpusValues.HUE_ENDPOINT);
        CommandEnvelope command = envelope(bareEntity, "identify", Map.of());

        handler.handle(command);

        assertThat(sent).as("the fence renders a verdict AFTER actuation, never instead of it")
                .hasSize(1);
        List<EventEnvelope> published = publisher.ofType(EventTypes.COMMAND_RESULT).toList();
        assertThat(published).hasSize(1);
        CommandResultEvent result = (CommandResultEvent) published.get(0).payload();
        assertThat(result.outcome()).isEqualTo("unconfirmed");
        assertThat(result.failureReason())
                .isNotBlank()
                .contains("no confirmation surface exists for 'identify'");
        assertThat(publisher.ofType(EventTypes.STATE_CONFIRMED).toList())
                .as("AMD-97-INV-01: identify NEVER yields state_confirmed")
                .isEmpty();
        // N-6 chaining holds on the fence path too.
        assertThat(published.get(0).causalContext().causationId())
                .isEqualTo(command.commandEventId());
    }

    @Test
    @DisplayName("a CONFIRMABLE command never renders the unconfirmed verdict")
    void confirmable_noImmediateVerdict() {
        handler.handle(envelope(hueEntity, "set_color_temperature",
                Map.of("kelvin", 4525)));

        assertThat(sent).hasSize(1);
        assertThat(results()).isEmpty();
    }

    @Test
    @DisplayName("unicast rejection: the failure surface renders command_result(rejected)")
    void unicastReject_publishesRejected() {
        dispatchAccepted = false;

        handler.handle(envelope(hueEntity, "turn_on", Map.of()));

        assertThat(results()).hasSize(1);
        assertThat(results().get(0).outcome()).isEqualTo("rejected");
        assertThat(results().get(0).failureReason()).contains("APS unicast rejected");
    }

    @Test
    @DisplayName("F-6: a collision-invalidated cache hint re-resolves via lookup — actuation goes to the CORRECT address and the cache heals")
    void staleAddress_reresolvedBeforeDispatch() {
        // A different device announces on the Hue's old address: the Hue record's
        // hint is invalidated (the reindex victim rule).
        cache.recordAnnounce(new IEEEAddress(0x00124B00AABBCCDDL), HUE_NWK);
        assertThat(cache.device(HUE).orElseThrow().networkAddress())
                .isEqualTo(ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN);

        handler.handle(envelope(hueEntity, "turn_on", Map.of()));

        assertThat(lookups).containsExactly(HUE);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).networkAddress())
                .as("the misdirected-actuation class: the frame rides the refreshed address")
                .isEqualTo(FRESH_NWK);
        assertThat(cache.device(HUE).orElseThrow().networkAddress())
                .isEqualTo(FRESH_NWK);
        assertThat(results()).isEmpty();
    }

    @Test
    @DisplayName("a missing entity binding renders unroutable — a data-absence case never throws")
    void missingBinding_unroutableResult() {
        handler.handle(envelope(EntityId.of(UlidFactory.generate(clock)),
                "turn_on", Map.of()));

        assertThat(sent).isEmpty();
        assertThat(results()).hasSize(1);
        assertThat(results().get(0).outcome()).isEqualTo("unroutable");
    }

    @Test
    @DisplayName("an unmapped command throws UOE (→ PERMANENT at the classifier, §1.5) — deliberate, not a data absence")
    void unsupportedCommand_throws() {
        assertThatThrownBy(() ->
                handler.handle(envelope(hueEntity, "self_destruct", Map.of())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("self_destruct");
    }

    // ── M9.5-DURb §5 (DP-B6) — the fence reads the INSTALLED policy first ───

    /**
     * Rebuilds the registry-held entity with one capability's policy replaced —
     * the test-tree stand-in for a §3 posture-routing outcome (direct registry
     * writes are test-tree-only; the arch rule scans production code).
     */
    private void installPolicy(EntityId entityId, String capabilityId,
            ConfirmationPolicy policy) {
        Entity entity = entityRegistry.findEntity(entityId).orElseThrow();
        List<CapabilityInstance> rebuilt = entity.capabilities().stream()
                .map(instance -> instance.capabilityId().equals(capabilityId)
                        ? new CapabilityInstance(instance.capabilityId(),
                                instance.version(), instance.namespace(),
                                instance.featureMap(), instance.attributes(),
                                instance.commands(), policy)
                        : instance)
                .toList();
        entityRegistry.updateEntity(new Entity(entity.entityId(),
                entity.entitySlug(), entity.entityType(), entity.displayName(),
                entity.deviceId(), entity.endpointIndex(), entity.areaId(),
                entity.enabled(), entity.labels(), rebuilt, entity.entityRole(),
                entity.createdAt()));
    }

    @Test
    @DisplayName("DP-B6: an installed-DISABLED actuator policy renders the immediate "
            + "honest unconfirmed verdict — dispatched, never tracked, NEVER silent "
            + "(the M9.4-RPT posture-DISABLED silent class, closed)")
    void installedDisabledPolicy_rendersHonestVerdict() {
        // A bare Hue (no profile match anywhere): no characterization exists, and
        // turn_on is not inherently unconfirmable — the pre-DURb fence had
        // NOTHING to say for a posture-downgraded actuator and fell silent.
        IEEEAddress bare = new IEEEAddress(0x0017880109AB77AAL);
        InterviewResult interview = new InterviewResult(bare, 0x4321,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(MeasuredCorpusValues.HUE_ENDPOINT,
                        0x0104, 0x010D,
                        List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008,
                                0x0300, 0x1000, 0xFC01, 0xFC04),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "LCA017", 1, InterviewStatus.COMPLETE);
        cache.recordInterview(interview, null);
        slice.onDeviceDiscovered(interview, null);
        EntityId bareEntity = slice.adopt(bare).entityIds()
                .get(MeasuredCorpusValues.HUE_ENDPOINT);
        installPolicy(bareEntity, "on_off", new ConfirmationPolicy(
                ConfirmationMode.DISABLED, List.of(), null, 5000L));
        CommandEnvelope command = envelope(bareEntity, "turn_on", Map.of());

        handler.handle(command);

        assertThat(sent).as("actuation is never gated by the fence").hasSize(1);
        List<EventEnvelope> published =
                publisher.ofType(EventTypes.COMMAND_RESULT).toList();
        assertThat(published).hasSize(1);
        CommandResultEvent result = (CommandResultEvent) published.get(0).payload();
        assertThat(result.outcome()).isEqualTo("unconfirmed");
        assertThat(result.failureReason())
                .as("the recorded reason names the installed policy")
                .contains("installed confirmation policy")
                .contains("on_off")
                .contains("not tracked");
        assertThat(publisher.ofType(EventTypes.STATE_CONFIRMED).toList())
                .as("AMD-97-INV-01: installed-DISABLED never yields state_confirmed")
                .isEmpty();
        // N-6 chaining holds on the installed arm too.
        assertThat(published.get(0).causalContext().causationId())
                .isEqualTo(command.commandEventId());
    }

    @Test
    @DisplayName("DP-B6: the INSTALLED policy wins over the profile characterization — "
            + "a kept (non-DISABLED) policy renders NO immediate verdict even where "
            + "the characterization says UNCONFIRMABLE")
    void installedKeptPolicy_winsOverCharacterization() {
        // The characterized Hue installs identify as DISABLED at adoption; a kept
        // policy here means the installed surface DISAGREES with the profile file
        // — the durable per-device tuning is the truth the fence reads first.
        installPolicy(hueEntity, "identify", new ConfirmationPolicy(
                ConfirmationMode.ANY_CHANGE, List.of("identify_active"), null,
                5000L));

        handler.handle(envelope(hueEntity, "identify", Map.of()));

        assertThat(sent).hasSize(1);
        assertThat(results())
                .as("a kept installed policy hands the outcome to the confirmation "
                        + "window — the characterization no longer overrides it")
                .isEmpty();
    }

    @Test
    @DisplayName("DP-B6: a kept installed policy carries the DP-a tuned timeout and "
            + "renders no immediate verdict — the window owns the outcome")
    void installedTunedPolicy_timeoutHonored_noImmediateVerdict() {
        Entity entity = entityRegistry.findEntity(hueEntity).orElseThrow();
        CapabilityInstance colorTemperature = entity.capabilities().stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow();
        assertThat(colorTemperature.confirmation().defaultTimeoutMs())
                .as("the measured 15 s window is the installed truth the fence reads")
                .isEqualTo(15000L);

        handler.handle(envelope(hueEntity, "set_color_temperature",
                Map.of("kelvin", 4525)));

        assertThat(sent).hasSize(1);
        assertThat(results()).isEmpty();
    }

    @Test
    @DisplayName("DP-B6: with no registry entity the fence falls back to the profile "
            + "characterization (the pre-adoption shape) — regression")
    void noRegistryEntity_fallsBackToCharacterization() {
        entityRegistry.removeEntity(hueEntity);   // test-tree write: no-entity shape

        handler.handle(envelope(hueEntity, "identify", Map.of()));

        assertThat(sent).hasSize(1);
        assertThat(results()).hasSize(1);
        assertThat(results().get(0).outcome()).isEqualTo("unconfirmed");
        assertThat(results().get(0).failureReason())
                .as("the characterization fallback still renders the measured note")
                .contains("no report");
    }
}
