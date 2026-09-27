/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.EnergyMeter;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IR-61 — {@link RegistryStalenessResolver}: Doc 03 §3.8's threshold chain over the entity
 * registry and the capability catalog. The per-entity override first, then the SMALLEST
 * interval the entity's capabilities declare (among the ids the catalog knows), then the
 * global default, then empty — the entity is never stale.
 */
@DisplayName("RegistryStalenessResolver — Doc 03 §3.8's threshold chain (IR-61)")
final class RegistryStalenessResolverTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration POWER_METER = Duration.ofSeconds(1200);
    private static final Duration ENERGY_METER = Duration.ofSeconds(7200);
    private static final Duration GLOBAL = Duration.ofSeconds(600);

    /** on_off + power_meter + energy_meter — the Gen4's shape. */
    private static final EntityId PLUG = new EntityId(new Ulid(0x61L, 0x1L));
    private static final EntityId ENERGY_ONLY = new EntityId(new Ulid(0x61L, 0x2L));
    /** on_off + brightness — capabilities that declare nothing. */
    private static final EntityId LIGHT = new EntityId(new Ulid(0x61L, 0x3L));
    /** One capability id no catalog knows. */
    private static final EntityId CUSTOM = new EntityId(new Ulid(0x61L, 0x4L));
    private static final EntityId UNREGISTERED = new EntityId(new Ulid(0x61L, 0x9L));

    private InMemoryEntityRegistry registry;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryStalenessResolverTest() {
    }

    @BeforeEach
    void setUp() {
        registry = new InMemoryEntityRegistry();
        register(PLUG, EntityType.PLUG, List.of(instanceOf(StandardCapabilities.onOff()),
                instanceOf(StandardCapabilities.powerMeter()),
                instanceOf(StandardCapabilities.energyMeter())));
        register(ENERGY_ONLY, EntityType.ENERGY_METER,
                List.of(instanceOf(StandardCapabilities.energyMeter())));
        register(LIGHT, EntityType.LIGHT, List.of(instanceOf(StandardCapabilities.onOff()),
                instanceOf(StandardCapabilities.brightness())));
        register(CUSTOM, EntityType.SENSOR, List.of(new CapabilityInstance(
                "acme_flow_meter", 1, "acme", 0, Map.of(), Map.of(), null)));
    }

    @Test
    @DisplayName("T1: the per-entity override outranks the capability default and the global")
    void overrideOutranksCapabilityAndGlobal() {
        RegistryStalenessResolver resolver = new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(PLUG, Duration.ofSeconds(45)),
                Optional.of(GLOBAL));

        assertThat(resolver.thresholdFor(PLUG)).contains(Duration.ofSeconds(45));
        assertThat(resolver.thresholdFor(ENERGY_ONLY))
                .as("the override is per entity: an entity without one resolves its capability")
                .contains(ENERGY_METER);
    }

    @Test
    @DisplayName("T1b: the smallest declared interval governs; nothing declared → the global "
            + "default, else empty; an id the catalog does not know contributes nothing")
    void smallestCapabilityIntervalGoverns() {
        RegistryStalenessResolver withGlobal = new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(), Optional.of(GLOBAL));
        assertThat(withGlobal.thresholdFor(PLUG))
                .as("power_meter 1200 s beside energy_meter 7200 s → the MINIMUM")
                .contains(POWER_METER);
        assertThat(withGlobal.thresholdFor(ENERGY_ONLY)).contains(ENERGY_METER);
        assertThat(withGlobal.thresholdFor(LIGHT)).contains(GLOBAL);
        assertThat(withGlobal.thresholdFor(CUSTOM)).contains(GLOBAL);

        RegistryStalenessResolver noGlobal = new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(), Optional.empty());
        assertThat(noGlobal.thresholdFor(LIGHT)).isEmpty();
        assertThat(noGlobal.thresholdFor(CUSTOM)).isEmpty();

        RegistryStalenessResolver energyCatalog = new RegistryStalenessResolver(registry,
                List.of(StandardCapabilities.energyMeter()), Map.of(), Optional.empty());
        assertThat(energyCatalog.thresholdFor(PLUG))
                .as("power_meter is not in this catalog: the resolver reads its catalog alone")
                .contains(ENERGY_METER);
    }

    @Test
    @DisplayName("T1c: an unknown entity falls through to the global default, else empty; "
            + "construction rejects a negative duration and a null global default")
    void unknownEntityFallsThrough() {
        assertThat(new RegistryStalenessResolver(registry, StandardCapabilities.all(), Map.of(),
                Optional.of(GLOBAL)).thresholdFor(UNREGISTERED)).contains(GLOBAL);
        assertThat(new RegistryStalenessResolver(registry, StandardCapabilities.all(), Map.of(),
                Optional.empty()).thresholdFor(UNREGISTERED)).isEmpty();
        assertThat(new RegistryStalenessResolver(registry, StandardCapabilities.all(),
                Map.of(PLUG, Duration.ZERO), Optional.empty()).thresholdFor(PLUG))
                .as("non-negative, not positive: a zero override is accepted")
                .contains(Duration.ZERO);

        assertThatThrownBy(() -> new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(PLUG, Duration.ofSeconds(-1)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(), Optional.of(Duration.ofSeconds(-1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistryStalenessResolver(registry,
                StandardCapabilities.all(), Map.of(), null))
                .as("the absence of a global default is Optional.empty(), never null")
                .isInstanceOf(NullPointerException.class);

        Capability impostor = new EnergyMeter("power_meter", 1, "core", Map.of(), Map.of(),
                StandardCapabilities.powerMeter().confirmationPolicy());
        assertThatThrownBy(() -> new RegistryStalenessResolver(registry,
                List.of(StandardCapabilities.powerMeter(), impostor), Map.of(),
                Optional.empty()))
                .as("one capability id declared twice with different intervals")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void register(EntityId id, EntityType type, List<CapabilityInstance> capabilities) {
        registry.createEntity(new Entity(id, "entity-" + id, type, "Entity " + id, null, 0,
                null, true, List.of(), capabilities, CREATED));
    }

    private static CapabilityInstance instanceOf(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }
}
