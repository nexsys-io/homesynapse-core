/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.AttributeType;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link Identify} capability shape pins (M9.4b §3.1, SD-3 — Doc 02 §3.8).
 *
 * <p>Identify is issuable-but-inherently-unconfirmable: it has NO attributes
 * (nothing to confirm, by construction) and a DISABLED confirmation policy at
 * the capability root, so the ledger never tracks it (AMD-97-INV-01
 * structural). The issuing adapter owns the immediate honest verdict — SD-3:
 * "an immediate rendered UNCONFIRMED verdict with recorded reason, not the
 * silent DISABLED bypass; never-tracked and honestly-verdicted are different
 * promises."</p>
 */
@DisplayName("Identify capability (M9.4b §3.1, SD-3)")
class IdentifyCapabilityTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    IdentifyCapabilityTest() {
    }

    @Test
    @DisplayName("identity: id 'identify', version 1, core namespace")
    void identityComponents() {
        Identify identify = StandardCapabilities.identify();

        assertThat(identify.capabilityId()).isEqualTo("identify");
        assertThat(identify.version()).isEqualTo(1);
        assertThat(identify.namespace()).isEqualTo("core");
    }

    @Test
    @DisplayName("NO attributes — identify has no state; nothing to confirm, by construction")
    void noAttributes() {
        assertThat(StandardCapabilities.identify().attributeSchemas()).isEmpty();
    }

    @Test
    @DisplayName("one command: identify(duration_s: INT 0-300, OPTIONAL), no outcomes, IDEMPOTENT")
    void identifyCommandShape() {
        CommandDefinition command =
                StandardCapabilities.identify().commandDefinitions().get("identify");

        assertThat(command).isNotNull();
        assertThat(command.commandType()).isEqualTo("identify");
        assertThat(command.expectedOutcomes()).isEmpty();
        assertThat(command.idempotencyClass()).isEqualTo(IdempotencyClass.IDEMPOTENT);
        assertThat(command.parameters()).hasSize(1);

        ParameterSchema duration = command.parameters().get(0);
        // The param name matches the zigbee adapter's pin ("duration_s"); it is
        // OPTIONAL — the adapter defaults the duration when absent. Bounds
        // 0-300 s are a chosen sane cap on ZCL's u16 identifyTime.
        assertThat(duration.parameterName()).isEqualTo("duration_s");
        assertThat(duration.type()).isEqualTo(AttributeType.INT);
        assertThat(duration.minimum()).isEqualTo(0);
        assertThat(duration.maximum()).isEqualTo(300);
        assertThat(duration.required()).isFalse();
    }

    @Test
    @DisplayName("confirmation is DISABLED at the capability root — never-tracked is structural")
    void confirmationDisabled() {
        ConfirmationPolicy policy = StandardCapabilities.identify().confirmationPolicy();

        assertThat(policy.mode()).isEqualTo(ConfirmationMode.DISABLED);
        assertThat(policy.authoritativeAttributes()).isEmpty();
        assertThat(policy.defaultTolerance()).isNull();
        assertThat(policy.defaultTimeoutMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Identify rides all() — the catalogue includes it")
    void inCatalogue() {
        assertThat(StandardCapabilities.all())
                .anyMatch(c -> c instanceof Identify);
    }
}
