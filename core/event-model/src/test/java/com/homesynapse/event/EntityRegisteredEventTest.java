/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.BooleanValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EntityRegisteredEvent} and its capability mirror family —
 * the AMD-99 full-fidelity entity registration payload. The mirrors must carry
 * everything the registries hold (capabilities and the installed DP-a
 * confirmation tuning included) so replay alone rebuilds both registries.
 */
@DisplayName("EntityRegisteredEvent")
class EntityRegisteredEventTest {

    private static final Ulid ENTITY_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final Ulid DEVICE_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static CapabilityInstanceRef onOffRef() {
        return new CapabilityInstanceRef(
                "on_off",
                1,
                "core",
                0,
                Map.of("on", new AttributeSchemaRef(
                        "on", AttributeType.BOOLEAN, null, null, null, null,
                        null, null, List.of("READ", "NOTIFY"), false, true)),
                Map.of("turn_on", new CommandDefinitionRef(
                        "turn_on",
                        List.of(),
                        0,
                        List.of(new ExpectedOutcomeRef(
                                "on",
                                new ExpectationRef.ExactMatchRef(new BooleanValue(true)),
                                5000L)),
                        Duration.ofSeconds(5),
                        "IDEMPOTENT")),
                new ConfirmationPolicyRef("EXACT_MATCH", List.of("on"), null, 5000L));
    }

    private static EntityRegisteredEvent event(List<CapabilityInstanceRef> capabilities) {
        return new EntityRegisteredEvent(
                ENTITY_ID,
                "zigbee-0011223344556677-ep1",
                "LIGHT",
                "Signify Hue Bulb",
                DEVICE_ID,
                1,
                null,
                true,
                List.of(),
                "PRIMARY",
                CREATED_AT,
                capabilities);
    }

    @Test
    @DisplayName("carries @EventType(entity_registered) and implements DomainEvent")
    void annotatedAndTyped() {
        EventType annotation = EntityRegisteredEvent.class.getAnnotation(EventType.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(EventTypes.ENTITY_REGISTERED);
        assertThat(DomainEvent.class).isAssignableFrom(EntityRegisteredEvent.class);
    }

    @Test
    @DisplayName("no mirror record carries @EventType or implements DomainEvent (41->43 pin guard)")
    void mirrorsAreNotEvents() {
        List<Class<?>> mirrors = List.of(
                HardwareIdentifierRef.class,
                CapabilityInstanceRef.class,
                AttributeSchemaRef.class,
                CommandDefinitionRef.class,
                ParameterSchemaRef.class,
                ExpectedOutcomeRef.class,
                ExpectationRef.class,
                ExpectationRef.ExactMatchRef.class,
                ExpectationRef.WithinToleranceRef.class,
                ExpectationRef.EnumTransitionRef.class,
                ExpectationRef.AnyChangeRef.class,
                ConfirmationPolicyRef.class);

        for (Class<?> mirror : mirrors) {
            assertThat(mirror.getAnnotation(EventType.class))
                    .as("%s must not carry @EventType", mirror.getSimpleName())
                    .isNull();
            assertThat(DomainEvent.class.isAssignableFrom(mirror))
                    .as("%s must not implement DomainEvent", mirror.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("capabilities are carried in order, defensively copied, unmodifiable")
    void capabilitiesCopied() {
        EntityRegisteredEvent registered = event(List.of(onOffRef()));

        assertThat(registered.capabilities()).hasSize(1);
        assertThatThrownBy(() -> registered.capabilities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registered.labels().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AttributeSchemaRef sorts permissions and validValues; null validValues passes through")
    void attributeSchemaRef_sortsSetDerivedLists() {
        AttributeSchemaRef sorted = new AttributeSchemaRef(
                "mode", AttributeType.ENUM, null, null, null,
                List.of("heat", "auto", "cool"),
                null, null, List.of("WRITE", "READ"), false, true);

        assertThat(sorted.validValues()).containsExactly("auto", "cool", "heat");
        assertThat(sorted.permissions()).containsExactly("READ", "WRITE");

        AttributeSchemaRef nonEnum = new AttributeSchemaRef(
                "on", AttributeType.BOOLEAN, null, null, null, null,
                null, null, List.of("READ"), false, true);
        assertThat(nonEnum.validValues()).isNull();
    }

    @Test
    @DisplayName("Number components canonicalize to the Jackson-native pair (Integer/Long | Double)")
    void numberComponents_canonicalize() {
        AttributeSchemaRef schema = new AttributeSchemaRef(
                "brightness", AttributeType.INT,
                (short) 0, 254L, 1.5f,
                null, null, null, List.of("READ", "WRITE"), false, true);

        // Short -> Integer; in-int-range Long -> Integer; Float -> Double. This is
        // what makes a decoded payload structurally EQUAL to the emitted one — the
        // codec's integral tokens come back Integer and decimals come back Double.
        assertThat(schema.minimum()).isEqualTo(0);
        assertThat(schema.minimum()).isExactlyInstanceOf(Integer.class);
        assertThat(schema.maximum()).isEqualTo(254);
        assertThat(schema.maximum()).isExactlyInstanceOf(Integer.class);
        assertThat(schema.step()).isEqualTo(1.5d);
        assertThat(schema.step()).isExactlyInstanceOf(Double.class);

        ParameterSchemaRef parameter = new ParameterSchemaRef(
                "level", AttributeType.INT, 0, 100, true, 0, null);
        assertThat(parameter.minimum()).isExactlyInstanceOf(Integer.class);

        ConfirmationPolicyRef policy =
                new ConfirmationPolicyRef("TOLERANCE", List.of("brightness"), 2L, 30000L);
        assertThat(policy.defaultTolerance()).isEqualTo(2);
        assertThat(policy.defaultTolerance()).isExactlyInstanceOf(Integer.class);
    }

    @Test
    @DisplayName("CapabilityInstanceRef map components are key-sorted, unmodifiable copies")
    void capabilityInstanceRef_mapsSortedAndUnmodifiable() {
        CapabilityInstanceRef ref = onOffRef();

        assertThatThrownBy(() -> ref.attributes().remove("on"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ref.commands().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("ExpectationRef is sealed with exactly the four domain permits")
    void expectationRef_sealedWithFourPermits() {
        assertThat(ExpectationRef.class.isSealed()).isTrue();
        assertThat(ExpectationRef.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
                ExpectationRef.ExactMatchRef.class,
                ExpectationRef.WithinToleranceRef.class,
                ExpectationRef.EnumTransitionRef.class,
                ExpectationRef.AnyChangeRef.class);
    }

    @Test
    @DisplayName("required components reject null; entityRole is required (resolved, never null)")
    void requiredComponentsRejectNull() {
        assertThatThrownBy(() -> new EntityRegisteredEvent(
                null, "slug", "LIGHT", "name", DEVICE_ID, 1, null, true,
                List.of(), "PRIMARY", CREATED_AT, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityId");
        assertThatThrownBy(() -> new EntityRegisteredEvent(
                ENTITY_ID, "slug", "LIGHT", "name", null, 1, null, true,
                List.of(), "PRIMARY", CREATED_AT, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceId");
        assertThatThrownBy(() -> new EntityRegisteredEvent(
                ENTITY_ID, "slug", "LIGHT", "name", DEVICE_ID, 1, null, true,
                List.of(), null, CREATED_AT, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityRole");
        assertThatThrownBy(() -> new EntityRegisteredEvent(
                ENTITY_ID, "slug", "LIGHT", "name", DEVICE_ID, 1, null, true,
                List.of(), "PRIMARY", CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capabilities");
    }
}
