/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.IntegrationStarted;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.StringValue;
import com.homesynapse.platform.identity.Ulid;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Full round-trip tests for {@link EventPayloadCodec} across the original 36
 * registered event types (24 core + 10 integration lifecycle + 2 capability) — the
 * registry now holds 49 after the M7.1 + M7.2 automation slices (AMD-92), whose 13 new
 * records round-trip in {@code AutomationEventSerdeTest} — plus DegradedEvent fallback
 * verification (DECIDE-M2-06, DECIDE-M2-07) and SNAKE_CASE property naming verification.
 */
@DisplayName("EventPayloadCodec")
class EventPayloadCodecTest {

    private EventTypeRegistry registry;
    private EventPayloadCodec codec;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
        registry = new EventTypeRegistry(AllEventClasses.ALL_EVENTS);
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        codec = new EventPayloadCodec(registry, warmup);
    }

    // ===== Round-trip helper =====

    private <T extends DomainEvent> void assertRoundTrip(T original, String expectedType) throws Exception {
        byte[] bytes = codec.encode(original);
        assertThat(bytes).isNotNull().isNotEmpty();

        DomainEvent decoded = codec.decode(expectedType, 1, bytes);
        assertThat(decoded)
                .as("decoded should equal original for %s", original.getClass().getSimpleName())
                .isEqualTo(original);
    }

    // ===== Core event round-trips (24) =====

    @Nested
    @DisplayName("core event round-trips")
    class CoreEvents {

        @Test
        void commandIssued() throws Exception {
            assertRoundTrip(TestEventSamples.commandIssued(), EventTypes.COMMAND_ISSUED);
        }

        @Test
        void commandDispatched() throws Exception {
            assertRoundTrip(TestEventSamples.commandDispatched(), EventTypes.COMMAND_DISPATCHED);
        }

        @Test
        void commandResult() throws Exception {
            assertRoundTrip(TestEventSamples.commandResult(), EventTypes.COMMAND_RESULT);
        }

        @Test
        void commandConfirmationTimedOut() throws Exception {
            assertRoundTrip(
                    TestEventSamples.commandConfirmationTimedOut(),
                    EventTypes.COMMAND_CONFIRMATION_TIMED_OUT);
        }

        @Test
        void stateReported() throws Exception {
            assertRoundTrip(TestEventSamples.stateReported(), EventTypes.STATE_REPORTED);
        }

        @Test
        void stateReportRejected() throws Exception {
            assertRoundTrip(
                    TestEventSamples.stateReportRejected(),
                    EventTypes.STATE_REPORT_REJECTED);
        }

        @Test
        void stateChanged() throws Exception {
            // AMD-52: the typed StateChangedEvent payload is written at schema_version 2.
            // (Round-tripping it at version 1 would hit the Path-B legacy gate — see the
            // AMD-52 nested class below.)
            StateChangedEvent original = TestEventSamples.stateChanged();
            byte[] bytes = codec.encode(original);
            DomainEvent decoded = codec.decode(EventTypes.STATE_CHANGED, 2, bytes);
            assertThat(decoded).isEqualTo(original);
        }

        @Test
        void stateConfirmed() throws Exception {
            assertRoundTrip(TestEventSamples.stateConfirmed(), EventTypes.STATE_CONFIRMED);
        }

        @Test
        void deviceDiscovered() throws Exception {
            assertRoundTrip(TestEventSamples.deviceDiscovered(), EventTypes.DEVICE_DISCOVERED);
        }

        @Test
        void deviceAdopted() throws Exception {
            assertRoundTrip(TestEventSamples.deviceAdopted(), EventTypes.DEVICE_ADOPTED);
        }

        @Test
        void deviceRemoved() throws Exception {
            assertRoundTrip(TestEventSamples.deviceRemoved(), EventTypes.DEVICE_REMOVED);
        }

        @Test
        void availabilityChanged() throws Exception {
            assertRoundTrip(
                    TestEventSamples.availabilityChanged(),
                    EventTypes.AVAILABILITY_CHANGED);
        }

        @Test
        void automationTriggered() throws Exception {
            assertRoundTrip(
                    TestEventSamples.automationTriggered(),
                    EventTypes.AUTOMATION_TRIGGERED);
        }

        @Test
        void automationCompleted() throws Exception {
            assertRoundTrip(
                    TestEventSamples.automationCompleted(),
                    EventTypes.AUTOMATION_COMPLETED);
        }

        @Test
        void automationCompletedWithFailure() throws Exception {
            assertRoundTrip(
                    TestEventSamples.automationCompletedWithFailure(),
                    EventTypes.AUTOMATION_COMPLETED);
        }

        @Test
        void presenceSignal() throws Exception {
            assertRoundTrip(TestEventSamples.presenceSignal(), EventTypes.PRESENCE_SIGNAL);
        }

        @Test
        void presenceChanged() throws Exception {
            assertRoundTrip(TestEventSamples.presenceChanged(), EventTypes.PRESENCE_CHANGED);
        }

        @Test
        void systemStarted() throws Exception {
            assertRoundTrip(TestEventSamples.systemStarted(), EventTypes.SYSTEM_STARTED);
        }

        @Test
        void systemStopped() throws Exception {
            assertRoundTrip(TestEventSamples.systemStopped(), EventTypes.SYSTEM_STOPPED);
        }

        @Test
        void storagePressureChanged() throws Exception {
            assertRoundTrip(
                    TestEventSamples.storagePressureChanged(),
                    EventTypes.STORAGE_PRESSURE_CHANGED);
        }

        @Test
        void configChanged() throws Exception {
            assertRoundTrip(TestEventSamples.configChanged(), EventTypes.CONFIG_CHANGED);
        }

        @Test
        void configError() throws Exception {
            assertRoundTrip(TestEventSamples.configError(), EventTypes.CONFIG_ERROR);
        }

        @Test
        void configValidationCompleted() throws Exception {
            // AMD-70: the Map<String, Integer> severityCounts component must
            // survive the codec round-trip losslessly.
            assertRoundTrip(
                    TestEventSamples.configValidationCompleted(),
                    EventTypes.CONFIG_VALIDATION_COMPLETED);
        }

        @Test
        void configSectionReloaded() throws Exception {
            assertRoundTrip(
                    TestEventSamples.configSectionReloaded(),
                    EventTypes.CONFIG_SECTION_RELOADED);
        }

        @Test
        void telemetrySummary() throws Exception {
            assertRoundTrip(
                    TestEventSamples.telemetrySummary(),
                    EventTypes.TELEMETRY_SUMMARY);
        }
    }

    // ===== Integration event round-trips (5) =====

    @Nested
    @DisplayName("integration lifecycle event round-trips")
    class IntegrationEvents {

        @Test
        void integrationStarted() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationStarted(),
                    EventTypes.INTEGRATION_STARTED);
        }

        @Test
        void integrationStopped() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationStopped(),
                    EventTypes.INTEGRATION_STOPPED);
        }

        @Test
        void integrationHealthChanged() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationHealthChanged(),
                    EventTypes.INTEGRATION_HEALTH_CHANGED);
        }

        @Test
        void integrationRestarted() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationRestarted(),
                    EventTypes.INTEGRATION_RESTARTED);
        }

        @Test
        void integrationResourceExceeded() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationResourceExceeded(),
                    EventTypes.INTEGRATION_RESOURCE_EXCEEDED);
        }

        // --- AMD-58: five new dot-namespaced lifecycle events ---

        @Test
        void integrationConfigUpdated() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationConfigUpdated(),
                    EventTypes.INTEGRATION_CONFIG_UPDATED);
        }

        @Test
        void integrationOptionsUpdated() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationOptionsUpdated(),
                    EventTypes.INTEGRATION_OPTIONS_UPDATED);
        }

        @Test
        void integrationReauthRequired() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationReauthRequired(),
                    EventTypes.INTEGRATION_REAUTH_REQUIRED);
        }

        @Test
        void integrationReauthCompleted() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationReauthCompleted(),
                    EventTypes.INTEGRATION_REAUTH_COMPLETED);
        }

        @Test
        void integrationMigrationCompleted() throws Exception {
            assertRoundTrip(
                    TestEventSamples.integrationMigrationCompleted(),
                    EventTypes.INTEGRATION_MIGRATION_COMPLETED);
        }
    }

    // ===== Capability event round-trips (2) — AMD-59 =====

    @Nested
    @DisplayName("capability event round-trips")
    class CapabilityEvents {

        /** Creates a new test instance. */
        CapabilityEvents() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("capability.added with a command-less instance round-trips losslessly")
        void capabilityAdded_occupancy_roundTrips() throws Exception {
            // The occupancy-derived instance has an empty commands map, so its subtree
            // embeds no Expectation/AttributeValue and round-trips through the existing
            // PersistenceJacksonModule (AttributeSchema + ConfirmationPolicy are plain
            // Jackson-serializable). Proves AMD-59-INV-02 for command-less capabilities.
            assertRoundTrip(TestEventSamples.capabilityAdded(), EventTypes.CAPABILITY_ADDED);
        }

        @Test
        void capabilityRemoved() throws Exception {
            assertRoundTrip(TestEventSamples.capabilityRemoved(), EventTypes.CAPABILITY_REMOVED);
        }

        @Test
        @DisplayName("capability.added with a command-bearing instance round-trips (AMD-87 acceptance)")
        void capabilityAdded_onOff_roundTrips() throws Exception {
            assertRoundTrip(TestEventSamples.capabilityAddedOnOff(), EventTypes.CAPABILITY_ADDED);
        }
    }

    // ===== SNAKE_CASE property-naming verification =====

    @Nested
    @DisplayName("SNAKE_CASE property naming")
    class SnakeCaseNaming {

        @Test
        @DisplayName("CommandIssuedEvent fields are emitted as snake_case")
        void snakeCase_commandIssued() throws Exception {
            CommandIssuedEvent event = TestEventSamples.commandIssued();

            byte[] bytes = codec.encode(event);
            String json = new String(bytes, StandardCharsets.UTF_8);

            assertThat(json).contains("\"target_entity_ref\"");
            assertThat(json).contains("\"command_type\"");
            assertThat(json).contains("\"parameters\"");
            assertThat(json).contains("\"confirmation_timeout_ms\"");
            assertThat(json).contains("\"idempotency_class\"");
            assertThat(json).doesNotContain("\"targetEntityRef\"");
            assertThat(json).doesNotContain("\"commandType\"");
            assertThat(json).doesNotContain("\"confirmationTimeoutMs\"");
            assertThat(json).doesNotContain("\"idempotencyClass\"");
        }

        @Test
        @DisplayName("IntegrationStarted fields are emitted in snake_case")
        void snakeCase_integrationStarted() throws Exception {
            IntegrationStarted event = TestEventSamples.integrationStarted();

            byte[] bytes = codec.encode(event);
            String json = new String(bytes, StandardCharsets.UTF_8);

            assertThat(json).contains("\"integration_id\"");
            assertThat(json).contains("\"integration_type\"");
            assertThat(json).contains("\"new_state\"");
            assertThat(json).doesNotContain("\"integrationId\"");
            assertThat(json).doesNotContain("\"integrationType\"");
            assertThat(json).doesNotContain("\"newState\"");
            // IntegrationStarted has no previousState field — confirms the 4-field shape
            assertThat(json).doesNotContain("previous_state");
        }

        @Test
        @DisplayName("round-trip through snake_case JSON preserves all values")
        void snakeCase_roundTrip_preservesValues() throws Exception {
            IntegrationHealthChanged original = TestEventSamples.integrationHealthChanged();

            byte[] bytes = codec.encode(original);
            IntegrationHealthChanged parsed = (IntegrationHealthChanged)
                    codec.decode(EventTypes.INTEGRATION_HEALTH_CHANGED, 1, bytes);

            assertThat(parsed).isEqualTo(original);
        }
    }

    // ===== DegradedEvent fallback paths =====

    @Nested
    @DisplayName("DegradedEvent fallback")
    class DegradedFallback {

        @Test
        @DisplayName("unknown event type produces a DegradedEvent with the raw payload")
        void decode_unknownType_returnsDegradedEvent() {
            String rawJson = "{\"some_field\":\"some_value\"}";
            byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode("nonexistent_event", 1, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            DegradedEvent degraded = (DegradedEvent) decoded;
            assertThat(degraded.eventType()).isEqualTo("nonexistent_event");
            assertThat(degraded.schemaVersion()).isEqualTo(1);
            assertThat(degraded.rawPayload()).isEqualTo(rawJson);
            assertThat(degraded.failureReason()).contains("Unknown event type");
            assertThat(degraded.failureReason()).contains("nonexistent_event");
        }

        @Test
        @DisplayName("malformed JSON for a known type produces a DegradedEvent")
        void decode_malformedJson_returnsDegradedEvent() {
            String rawJson = "{this is not valid json";
            byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode(EventTypes.COMMAND_ISSUED, 1, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            DegradedEvent degraded = (DegradedEvent) decoded;
            assertThat(degraded.eventType()).isEqualTo(EventTypes.COMMAND_ISSUED);
            assertThat(degraded.rawPayload()).isEqualTo(rawJson);
            assertThat(degraded.failureReason()).isNotBlank();
        }

        @Test
        @DisplayName("compact-constructor validation failure produces a DegradedEvent")
        void decode_validationFailure_returnsDegradedEvent() {
            // CommandIssuedEvent requires a non-blank command_type — supply blank.
            String rawJson = """
                    {"target_entity_ref":"01ARZ3NDEKTSV4RRFFQ69G5FAV",
                     "command_type":"",
                     "parameters":"{}",
                     "confirmation_timeout_ms":1000,
                     "idempotency_class":"IDEMPOTENT"}
                    """;
            byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode(EventTypes.COMMAND_ISSUED, 1, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            DegradedEvent degraded = (DegradedEvent) decoded;
            assertThat(degraded.eventType()).isEqualTo(EventTypes.COMMAND_ISSUED);
            assertThat(degraded.failureReason()).isNotBlank();
        }

        @Test
        @DisplayName("empty byte array produces a DegradedEvent, not an exception")
        void decode_emptyPayload_returnsDegradedEvent() {
            byte[] empty = new byte[0];

            DomainEvent decoded = codec.decode(EventTypes.COMMAND_ISSUED, 1, empty);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
        }

        @Test
        @DisplayName("null eventType on corrupt row is clamped to 'unknown'")
        void decode_nullEventType_clampedToUnknown() {
            byte[] bytes = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode(null, 1, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            assertThat(((DegradedEvent) decoded).eventType()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("blank eventType on corrupt row is clamped to 'unknown'")
        void decode_blankEventType_clampedToUnknown() {
            byte[] bytes = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode("   ", 1, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            assertThat(((DegradedEvent) decoded).eventType()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("schemaVersion below 1 on corrupt row is clamped to 1")
        void decode_schemaVersionZero_clampedToOne() {
            byte[] bytes = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode("nonexistent_event", 0, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            assertThat(((DegradedEvent) decoded).schemaVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("negative schemaVersion on corrupt row is clamped to 1")
        void decode_negativeSchemaVersion_clampedToOne() {
            byte[] bytes = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode("nonexistent_event", -7, bytes);

            assertThat(decoded).isInstanceOf(DegradedEvent.class);
            assertThat(((DegradedEvent) decoded).schemaVersion()).isEqualTo(1);
        }
    }

    // ===== Nullable field round-trips =====

    @Nested
    @DisplayName("nullable field handling")
    class NullableFields {

        @Test
        @DisplayName("CommandResultEvent with null failureReason round-trips cleanly")
        void commandResult_nullFailureReason_roundTrips() throws Exception {
            CommandResultEvent original = new CommandResultEvent(
                    TestEventSamples.ULID_1, "turn_on", "success", null);

            byte[] bytes = codec.encode(original);
            String json = new String(bytes, StandardCharsets.UTF_8);
            // NON_NULL inclusion — null failureReason must be omitted from JSON
            assertThat(json).doesNotContain("failure_reason");

            DomainEvent decoded = codec.decode(EventTypes.COMMAND_RESULT, 1, bytes);
            assertThat(decoded).isEqualTo(original);
        }

        @Test
        @DisplayName("StateReportedEvent with all optional fields null round-trips")
        void stateReported_allOptionalNull_roundTrips() throws Exception {
            StateReportedEvent original = TestEventSamples.stateReported();

            byte[] bytes = codec.encode(original);
            DomainEvent decoded = codec.decode(EventTypes.STATE_REPORTED, 1, bytes);

            assertThat(decoded).isEqualTo(original);
        }

        @Test
        @DisplayName("AutomationCompletedEvent with null failure reason round-trips")
        void automationCompleted_nullReason_roundTrips() throws Exception {
            AutomationCompletedEvent original = TestEventSamples.automationCompleted();

            byte[] bytes = codec.encode(original);
            DomainEvent decoded = codec.decode(EventTypes.AUTOMATION_COMPLETED, 1, bytes);

            assertThat(decoded).isEqualTo(original);
        }
    }

    // ===== Encode error paths =====

    @Nested
    @DisplayName("encode error paths")
    class EncodeErrors {

        @Test
        @DisplayName("encode() rejects DegradedEvent — never re-serialized as a payload")
        void encode_degradedEvent_throwsIllegalArgument() {
            DegradedEvent degraded = new DegradedEvent("x", 1, "{}", "some reason");

            assertThatThrownBy(() -> codec.encode(degraded))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unregistered")
                    .hasMessageContaining(DegradedEvent.class.getName());
        }

        @Test
        @DisplayName("encode() throws NullPointerException on null payload")
        void encode_nullPayload_throws() {
            assertThatThrownBy(() -> codec.encode(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("decode() throws NullPointerException on null payload")
        void decode_nullPayload_throws() {
            assertThatThrownBy(() -> codec.decode(EventTypes.COMMAND_ISSUED, 1, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("payload");
        }
    }

    // ===== AMD-52: typed StateChangedEvent payload + schema-versioned replay =====

    @Nested
    @DisplayName("AMD-52 typed StateChangedEvent payload + Path-B replay")
    class Amd52TypedStateChanged {

        private static final EventId TRIGGER =
                EventId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"));

        @Test
        @DisplayName("§5#5: a typed FLOAT payload round-trips through decode(.., 2, ..) as its variant")
        void typedFloatPayload_roundTripsAtSchemaVersion2() {
            StateChangedEvent original = new StateChangedEvent(
                    "temperature_c", new FloatValue(20.0), new FloatValue(21.5), TRIGGER);

            byte[] bytes = codecEncode(original);
            String json = new String(bytes, StandardCharsets.UTF_8);
            // The hand-rolled tagged-union envelope appears inside the payload (SNAKE_CASE on
            // the outer record fields; literal "t"/"v" inside the AttributeValue envelope).
            assertThat(json).contains("\"new_value\"");
            assertThat(json).contains("\"t\":\"FLOAT\"");

            DomainEvent decoded = codec.decode(EventTypes.STATE_CHANGED, 2, bytes);
            assertThat(decoded).isEqualTo(original);
            assertThat(((StateChangedEvent) decoded).newValue()).isEqualTo(new FloatValue(21.5));
        }

        @Test
        @DisplayName("§5#5: nullable oldValue (first report) is omitted by NON_NULL and decodes to null")
        void nullOldValue_omittedAndDecodesToNull() {
            StateChangedEvent firstReport = new StateChangedEvent(
                    "power", null, new StringValue("on"), TRIGGER);

            byte[] bytes = codecEncode(firstReport);
            String json = new String(bytes, StandardCharsets.UTF_8);
            assertThat(json)
                    .as("NON_NULL inclusion omits the null old_value field")
                    .doesNotContain("old_value");

            DomainEvent decoded = codec.decode(EventTypes.STATE_CHANGED, 2, bytes);
            assertThat(decoded).isEqualTo(firstReport);
            assertThat(((StateChangedEvent) decoded).oldValue())
                    .as("an absent old_value decodes to null, not an error")
                    .isNull();
        }

        @Test
        @DisplayName("§5#6 (Path B): a legacy schema_version==1 String state_changed degrades to DegradedEvent")
        void legacyStringPayloadVersion1_degradesToDegradedEvent() {
            // A pre-AMD-52 row: String old/new value, written at schema_version 1.
            String rawJson = "{\"attribute_key\":\"power\",\"old_value\":\"off\","
                    + "\"new_value\":\"on\",\"triggered_by\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"}";
            byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);

            DomainEvent decoded = codec.decode(EventTypes.STATE_CHANGED, 1, bytes);

            assertThat(decoded)
                    .as("a v1 state_changed read under the typed reader is a defined DegradedEvent")
                    .isInstanceOf(DegradedEvent.class);
            DegradedEvent degraded = (DegradedEvent) decoded;
            assertThat(degraded.eventType()).isEqualTo(EventTypes.STATE_CHANGED);
            assertThat(degraded.schemaVersion()).isEqualTo(1);
            assertThat(degraded.rawPayload())
                    .as("the raw payload is preserved verbatim — no typed guess")
                    .isEqualTo(rawJson);
            assertThat(degraded.failureReason()).contains("schema_version 1");
            assertThat(degraded.failureReason()).contains("Path B");
        }

        @Test
        @DisplayName("§5#6 (Path B): the v1 gate does NOT fire for schema_version >= 2")
        void schemaVersion2_isNotPathBGated() {
            StateChangedEvent original = new StateChangedEvent(
                    "power", new StringValue("off"), new StringValue("on"), TRIGGER);
            byte[] bytes = codecEncode(original);

            DomainEvent decoded = codec.decode(EventTypes.STATE_CHANGED, 2, bytes);

            assertThat(decoded)
                    .as("version 2 is the typed reader, not Path B")
                    .isInstanceOf(StateChangedEvent.class);
        }

        private byte[] codecEncode(StateChangedEvent event) {
            try {
                return codec.encode(event);
            } catch (Exception e) {
                throw new AssertionError("encode failed", e);
            }
        }
    }

    // ===== Round-trip returns byte array, not string =====

    @Test
    @DisplayName("encode() returns UTF-8 bytes, decodable back as a String without error")
    void encode_returnsUtf8Bytes() throws Exception {
        CommandIssuedEvent event = TestEventSamples.commandIssued();

        byte[] bytes = codec.encode(event);
        String asString = new String(bytes, StandardCharsets.UTF_8);

        assertThat(asString).startsWith("{").endsWith("}");
    }

    // ===== Construction =====

    @Test
    @DisplayName("constructor throws NullPointerException on null registry")
    void construct_nullRegistry_throws() {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);

        assertThatThrownBy(() -> new EventPayloadCodec(null, warmup))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }

    @Test
    @DisplayName("constructor throws NullPointerException on null warmup")
    void construct_nullWarmup_throws() {
        assertThatThrownBy(() -> new EventPayloadCodec(registry, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("warmup");
    }
}
