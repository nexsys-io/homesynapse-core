/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Permits + construction checks for the AMD-88 trigger expansion. */
@DisplayName("TriggerDefinition permits (AMD-88)")
class TriggerDefinitionPermitTest {

    @Test
    @DisplayName("TriggerDefinition permits exactly 12 subtypes")
    void permitsTwelve() {
        assertThat(TriggerDefinition.class.getPermittedSubclasses()).hasSize(12);
    }

    @Test
    @DisplayName("the three new Tier-1 permits construct with a triggerId")
    void newPermitsConstruct() {
        var calendar = new CalendarTrigger(EntityId.of(Ulid.parse("00000000000000000000000000")),
                CalendarEventTransition.EVENT_START, Duration.ofMinutes(-15), "t-cal");
        var reachability = new ReachabilityTrigger(DeviceId.of(Ulid.parse("00000000000000000000000001")),
                Availability.UNAVAILABLE, Duration.ofMinutes(5), "t-reach");
        var manual = new ManualTrigger("ui-button", "t-manual");

        assertThat(calendar.triggerId()).isEqualTo("t-cal");
        assertThat(reachability.targetAvailability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(manual.invocationContext()).isEqualTo("ui-button");
    }

    @Test
    @DisplayName("ManualTrigger allows a null invocationContext")
    void manualNullContext() {
        assertThat(new ManualTrigger(null, "t").invocationContext()).isNull();
    }

    @Test
    @DisplayName("WebhookTrigger (promoted) carries its fields and defensively copies methods")
    void webhookFields() {
        WebhookTrigger webhook = new WebhookTrigger("hook-1", Set.of("POST", "PUT"), true, "t-web");

        assertThat(webhook.webhookId()).isEqualTo("hook-1");
        assertThat(webhook.allowedMethods()).containsExactlyInAnyOrder("POST", "PUT");
        assertThat(webhook.localOnly()).isTrue();
        assertThat(webhook.triggerId()).isEqualTo("t-web");
    }

    @Test
    @DisplayName("Tier-2 reserved permits remain empty records (no triggerId)")
    void tierTwoUnchanged() {
        assertThat(new TimeTrigger()).isNotNull();
        assertThat(new SunTrigger()).isNotNull();
        assertThat(new PresenceTrigger()).isNotNull();
        assertThat(TimeTrigger.class.getRecordComponents()).isEmpty();
        assertThat(PresenceTrigger.class.getRecordComponents()).isEmpty();
    }

    @Test
    @DisplayName("existing Tier-1 permits gained a triggerId component")
    void existingTierOneHaveTriggerId() {
        var trigger = new StateTrigger(new DirectRefSelector(
                EntityId.of(Ulid.parse("00000000000000000000000002"))),
                "on_off", "on", null, "t-state");
        assertThat(trigger.triggerId()).isEqualTo("t-state");
    }
}
