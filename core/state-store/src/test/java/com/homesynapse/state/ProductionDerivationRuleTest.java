/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.value.StringValue;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the typed {@link ProductionDerivationRule} via the
 * {@link DerivationRule#production(AttributeValueComparator, ComparisonPolicy,
 * AttributeSchemaResolver)} gateway (AMD-51 / M4.0b-3, §5 tests #2 and #7).
 *
 * <p>The rule is a pure function of {@code (priorState, envelope)} plus the injected schema
 * snapshot — no clock. Envelopes use a parsed literal {@code ingestTime}
 * ({@code NO_DIRECT_TIME_ACCESS} scans this non-whitelisted package's tests; literal parse is
 * allowed).</p>
 */
@DisplayName("ProductionDerivationRule (typed, AMD-51)")
class ProductionDerivationRuleTest {

    /** Literal fixture instant — parse is whitelisted by NO_DIRECT_TIME_ACCESS. */
    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    /** Typed rule wired with the standard capability schemas (temperature_c is FLOAT). */
    private final DerivationRule typedRule = DerivationRule.production(
            AttributeValueComparator.structural(),
            ComparisonPolicy.FP_NOISE_DEFAULT,
            AttributeSchemaResolver.of(StandardCapabilities.attributeSchemas()));

    @Test
    @DisplayName("first report (prior == null) emits a state_changed")
    void firstReportEmits() {
        EntityId id = entityId();
        List<EventDraft> drafts =
                typedRule.evaluate(new DerivationContext(null, reported(id, "temperature_c", "20.0")));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).payload()).isInstanceOf(StateChangedEvent.class);
    }

    @Test
    @DisplayName("within-epsilon FLOAT delta emits nothing")
    void withinEpsilonEmitsNothing() {
        EntityId id = entityId();
        EntityState prior = priorWith(id, "temperature_c", "20.0");
        List<EventDraft> drafts = typedRule.evaluate(
                new DerivationContext(prior, reported(id, "temperature_c", "20.0000000001")));
        assertThat(drafts)
                .as("|20.0 - 20.0000000001| = 1e-10 < the 1e-9 epsilon ⇒ no change")
                .isEmpty();
    }

    @Test
    @DisplayName("above-epsilon FLOAT delta emits a state_changed")
    void aboveEpsilonEmits() {
        EntityId id = entityId();
        EntityState prior = priorWith(id, "temperature_c", "20.0");
        List<EventDraft> drafts = typedRule.evaluate(
                new DerivationContext(prior, reported(id, "temperature_c", "20.0000001")));
        assertThat(drafts).hasSize(1);
    }

    @Test
    @DisplayName("the headline fix: 21.0 vs 21.00 are equal floats ⇒ no phantom change")
    void stringEquivalentNumbersSuppressed() {
        EntityId id = entityId();
        EntityState prior = priorWith(id, "temperature_c", "21.0");
        List<EventDraft> drafts = typedRule.evaluate(
                new DerivationContext(prior, reported(id, "temperature_c", "21.00")));
        assertThat(drafts)
                .as("21.0 and 21.00 differ as strings but are equal FLOATs (the AMD-51 fix)")
                .isEmpty();
    }

    @Test
    @DisplayName("§5 #7: the emitted StateChangedEvent carries String old/new and links to the cause")
    void preservesStringPayload() {
        EntityId id = entityId();
        EntityState prior = priorWith(id, "temperature_c", "20.0");
        EventEnvelope env = reported(id, "temperature_c", "21.5");

        List<EventDraft> drafts = typedRule.evaluate(new DerivationContext(prior, env));

        assertThat(drafts).hasSize(1);
        EventDraft draft = drafts.get(0);
        assertThat(draft.eventType()).isEqualTo(EventTypes.STATE_CHANGED);
        assertThat(draft.payload()).isInstanceOf(StateChangedEvent.class);
        StateChangedEvent sc = (StateChangedEvent) draft.payload();
        assertThat(sc.attributeKey()).isEqualTo("temperature_c");
        assertThat(sc.oldValue()).as("stringified prior").isEqualTo("20.0");
        assertThat(sc.newValue()).as("reported value verbatim — still String (AMD-52 is staged)")
                .isEqualTo("21.5");
        assertThat(sc.triggeredBy()).as("links to the triggering state_reported")
                .isEqualTo(env.eventId());
    }

    @Test
    @DisplayName("§5 #7: first-report payload carries an empty oldValue")
    void firstReportPayloadHasEmptyOldValue() {
        EntityId id = entityId();
        EventEnvelope env = reported(id, "temperature_c", "20.0");
        List<EventDraft> drafts = typedRule.evaluate(new DerivationContext(null, env));
        StateChangedEvent sc = (StateChangedEvent) drafts.get(0).payload();
        assertThat(sc.oldValue()).isEmpty();
        assertThat(sc.newValue()).isEqualTo("20.0");
    }

    @Test
    @DisplayName("no-arg production() is string-compare: 21.0 vs 21.00 emits (no schema, empty resolver)")
    void noArgProductionIsStringCompare() {
        DerivationRule stringRule = DerivationRule.production();
        EntityId id = entityId();
        EntityState prior = priorWith(id, "temperature_c", "21.0");
        List<EventDraft> drafts = stringRule.evaluate(
                new DerivationContext(prior, reported(id, "temperature_c", "21.00")));
        assertThat(drafts)
                .as("the empty-resolver gateway falls back to exact string compare (21.0 != 21.00)")
                .hasSize(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static EntityId entityId() {
        return new EntityId(UlidFactory.generate());
    }

    private static EventEnvelope reported(EntityId entityId, String key, String value) {
        EventId eventId = EventId.of(UlidFactory.generate());
        SubjectRef subject = SubjectRef.entity(entityId);
        return new EventEnvelope(
                eventId,
                EventTypes.STATE_REPORTED,
                1,
                FIXED,
                null,
                subject,
                1L,
                1L,
                EventPriority.DIAGNOSTIC,
                EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(eventId.value()),
                null,
                new StateReportedEvent(key, value, null, null, null));
    }

    private static EntityState priorWith(EntityId entityId, String key, String value) {
        return new EntityState(
                entityId,
                Map.of(key, new StringValue(value)),
                Availability.AVAILABLE,
                1L,
                FIXED,
                FIXED,
                FIXED,
                null,
                false);
    }
}
