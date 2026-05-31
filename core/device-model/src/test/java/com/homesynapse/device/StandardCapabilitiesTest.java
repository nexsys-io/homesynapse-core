/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.AttributeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StandardCapabilities} — the production standard-capability catalogue
 * lifted from {@code TestCapabilityFactory} (M4.0b-3 / DP-K).
 */
@DisplayName("StandardCapabilities catalogue (DP-K)")
class StandardCapabilitiesTest {

    @Test
    @DisplayName("all() returns the 15 core-namespace standard capabilities (no CustomCapability)")
    void allReturnsFifteenCoreCapabilities() {
        List<Capability> all = StandardCapabilities.all();
        assertThat(all).hasSize(15);
        assertThat(all).noneMatch(c -> c instanceof CustomCapability);
        assertThat(all).allMatch(c -> "core".equals(c.namespace()));
    }

    @Test
    @DisplayName("attributeSchemas() aggregates the standard keys with their declared types")
    void attributeSchemasAggregateWithExpectedTypes() {
        Map<String, AttributeSchema> schemas = StandardCapabilities.attributeSchemas();

        assertThat(schemas.get("temperature_c").type()).isEqualTo(AttributeType.FLOAT);
        assertThat(schemas.get("humidity_pct").type()).isEqualTo(AttributeType.FLOAT);
        assertThat(schemas.get("illuminance_lux").type()).isEqualTo(AttributeType.FLOAT);
        assertThat(schemas.get("energy_wh").type()).isEqualTo(AttributeType.FLOAT);
        assertThat(schemas.get("brightness").type()).isEqualTo(AttributeType.INT);
        assertThat(schemas.get("battery_pct").type()).isEqualTo(AttributeType.INT);
        assertThat(schemas.get("color_temp_kelvin").type()).isEqualTo(AttributeType.INT);
        assertThat(schemas.get("on").type()).isEqualTo(AttributeType.BOOLEAN);
        assertThat(schemas.get("direction").type()).isEqualTo(AttributeType.ENUM);
    }

    @Test
    @DisplayName("power_w is declared by two capabilities with the SAME type — no collision")
    void powerWattsIsConsistentAcrossCapabilities() {
        // PowerMeasurement and PowerMeter both declare power_w as FLOAT, so the aggregation
        // does not throw and the merged schema is FLOAT (the AMD-51 resolver's consistency
        // assumption holds for the standard catalogue).
        assertThat(StandardCapabilities.attributeSchemas().get("power_w").type())
                .isEqualTo(AttributeType.FLOAT);
    }

    @Test
    @DisplayName("at M4.0b-3 no standard attribute is QUANTITY/ARRAY/DEGRADED (all primitive)")
    void allStandardSchemasArePrimitiveTypes() {
        StandardCapabilities.attributeSchemas().values().forEach(s ->
                assertThat(s.type()).isIn(
                        AttributeType.BOOLEAN, AttributeType.INT, AttributeType.FLOAT,
                        AttributeType.STRING, AttributeType.ENUM));
    }

    @Test
    @DisplayName("attributeSchemas() returns an immutable snapshot")
    void attributeSchemasIsImmutable() {
        Map<String, AttributeSchema> schemas = StandardCapabilities.attributeSchemas();
        AttributeSchema sample = schemas.get("on");
        assertThatThrownBy(() -> schemas.put("injected", sample))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
