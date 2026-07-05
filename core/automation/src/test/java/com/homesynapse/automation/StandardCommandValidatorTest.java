/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.automation.CommandValidator.ValidationResult;
import com.homesynapse.device.Entity;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardCommandValidator} — capability/command existence + feature gating against a
 * fixed entity carrying the standard {@code on_off} capability.
 */
@DisplayName("StandardCommandValidator (M7.2a-2)")
class StandardCommandValidatorTest {

    private final EntityId entityId = AutomationTestSupport.entityId();
    private StandardCommandValidator validator;

    @BeforeEach
    void setUp() {
        Entity entity = AutomationTestSupport.entityWith(entityId,
                AutomationTestSupport.deviceId(), StandardCapabilities.onOff());
        validator = new StandardCommandValidator(
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)));
    }

    @Test
    @DisplayName("a command the entity's capability declares is valid")
    void declaredCommand_isValid() {
        ValidationResult result = validator.validate(entityId, "turn_on", Map.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("a command no capability declares is invalid with a reason")
    void unknownCommand_isInvalidWithReason() {
        ValidationResult result = validator.validate(entityId, "frobnicate", Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("frobnicate");
    }

    @Test
    @DisplayName("an unknown entity is invalid with a reason")
    void unknownEntity_isInvalid() {
        ValidationResult result = validator.validate(
                AutomationTestSupport.entityId(), "turn_on", Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("does not exist");
    }

    @Test
    @DisplayName("SD-3: an entity carrying the Identify capability accepts 'identify' through "
            + "the REAL Tier-1 floor — issuable, zero validator change")
    void identifyCapability_makesIdentifyIssuable() {
        EntityId light = AutomationTestSupport.entityId();
        Entity entity = AutomationTestSupport.entityWith(light,
                AutomationTestSupport.deviceId(), StandardCapabilities.identify());
        StandardCommandValidator withIdentify = new StandardCommandValidator(
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)));

        assertThat(withIdentify.validate(light, "identify", Map.of()).valid()).isTrue();
    }

    @Test
    @DisplayName("SD-3: 'color_loop' stays NON-issuable in Wave-1 — no core effects vocabulary "
            + "(the deliberate scope line)")
    void colorLoop_staysInvalid() {
        EntityId light = AutomationTestSupport.entityId();
        Entity entity = AutomationTestSupport.entityWith(light,
                AutomationTestSupport.deviceId(), StandardCapabilities.identify());
        StandardCommandValidator withIdentify = new StandardCommandValidator(
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)));

        ValidationResult result = withIdentify.validate(light, "color_loop", Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("color_loop");
    }
}
