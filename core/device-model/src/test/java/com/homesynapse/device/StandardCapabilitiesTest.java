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
    @DisplayName("all() returns the 16 core-namespace standard capabilities (no CustomCapability)")
    void allReturnsSixteenCoreCapabilities() {
        // 15 originals + Identify (M9.4b §3.1, SD-3).
        List<Capability> all = StandardCapabilities.all();
        assertThat(all).hasSize(16);
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

    @Test
    @DisplayName("brightness attribute is canonical 0-254 (Doc 08 §3.5); the set_brightness "
            + "parameter stays percent 0-100; tolerance 2 is LEVEL units (SD-2)")
    void brightnessAttributeIsCanonicalLevelDomain() {
        Brightness brightness = StandardCapabilities.brightness();

        // Doc 08 §3.5 :206 pins "brightness (IntValue, 0-254; percentage derived
        // at query time)" as CANONICAL — the attribute is the level domain.
        AttributeSchema attr = brightness.attributeSchemas().get("brightness");
        assertThat(attr.minimum()).isEqualTo(0);
        assertThat(attr.maximum()).isEqualTo(254);

        // The user-facing command parameter stays percent (the capability domain).
        ParameterSchema level = brightness.commandDefinitions().get("set_brightness")
                .parameters().get(0);
        assertThat(level.parameterName()).isEqualTo("level");
        assertThat(level.minimum()).isEqualTo(0);
        assertThat(level.maximum()).isEqualTo(100);

        // Doc 08 §392: "±2 of 128 ... account for rounding differences between
        // the 0-254 ZCL range" — the tolerance is attribute-domain (level units).
        ConfirmationPolicy policy = brightness.confirmationPolicy();
        assertThat(policy.mode()).isEqualTo(ConfirmationMode.TOLERANCE);
        assertThat(policy.authoritativeAttributes()).containsExactly("brightness");
        assertThat(policy.defaultTolerance()).isEqualTo(2);
        assertThat(policy.defaultTimeoutMs()).isEqualTo(5000L);
    }
}
