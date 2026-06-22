/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.StringValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link ComputedValue} resolution contract (M7.2b): each permit resolves totally and
 * deterministically over the single trigger-time snapshot (AMD-03; C-SA-2; INV-TO-02), with a
 * defined typed-absent/skip rule for absent or non-numeric inputs and no I/O collaborator. All
 * time is the injected {@link AutomationTestSupport#FIXED_INSTANT} (§4c).
 */
@DisplayName("ComputedValue (M7.2b computed-param seam)")
class ComputedValueTest {

    private final EntityId e1 = AutomationTestSupport.entityId();
    private final EntityId e2 = AutomationTestSupport.entityId();
    private final EntityId e3 = AutomationTestSupport.entityId();
    private final EntityId absentEntity = AutomationTestSupport.entityId();

    /** A snapshot: e1.power=10, e1.label="lamp", e2.power=20.5, e3.power="oops" (non-numeric). */
    private ComputedValueContext ctx() {
        EntityState s1 = AutomationTestSupport.state(e1, Availability.AVAILABLE,
                Map.of("power", new IntValue(10), "label", new StringValue("lamp")));
        EntityState s2 = AutomationTestSupport.state(e2, Availability.AVAILABLE,
                Map.of("power", new FloatValue(20.5)));
        EntityState s3 = AutomationTestSupport.state(e3, Availability.AVAILABLE,
                Map.of("power", new StringValue("oops")));
        StateSnapshot snapshot = AutomationTestSupport.snapshot(Map.of(e1, s1, e2, s2, e3, s3));
        return new ComputedValueContext(snapshot, AutomationTestSupport.FIXED_INSTANT);
    }

    // ---- LiteralValue -------------------------------------------------------

    @Test
    @DisplayName("LiteralValue resolves to its constant, independent of the snapshot")
    void literalValue_resolvesToConstant() {
        assertThat(new LiteralValue(new IntValue(42)).resolve(ctx())).isEqualTo(new IntValue(42));
        assertThat(new LiteralValue(new StringValue("on")).resolve(ctx()))
                .isEqualTo(new StringValue("on"));
    }

    // ---- AttributeRef -------------------------------------------------------

    @Test
    @DisplayName("AttributeRef present resolves to the entity's snapshot attribute value")
    void attributeRef_present_resolvesToSnapshotValue() {
        assertThat(new AttributeRef(e1, "power").resolve(ctx())).isEqualTo(new IntValue(10));
        assertThat(new AttributeRef(e1, "label").resolve(ctx())).isEqualTo(new StringValue("lamp"));
    }

    @Test
    @DisplayName("AttributeRef absent (attribute or entity) resolves to the typed-absent sentinel, never throws")
    void attributeRef_absent_resolvesToTypedAbsentSentinel() {
        AttributeValue missingAttribute = new AttributeRef(e1, "missing").resolve(ctx());
        AttributeValue missingEntity = new AttributeRef(absentEntity, "power").resolve(ctx());

        assertThat(missingAttribute).isInstanceOf(DegradedAttributeValue.class);
        assertThat(missingAttribute.attributeType()).isEqualTo(AttributeType.DEGRADED);
        assertThat(missingEntity).isInstanceOf(DegradedAttributeValue.class);
    }

    // ---- AggregateValue -----------------------------------------------------

    @Test
    @DisplayName("AggregateValue folds numeric members, skipping non-numeric and absent (SUM/AVG/MIN/MAX/COUNT)")
    void aggregateValue_foldsNumericMembersSkippingNonNumericAndAbsent() {
        // e1=10, e2=20.5 contribute; e3="oops" non-numeric -> skip; absentEntity -> skip.
        Set<EntityId> members = Set.of(e1, e2, e3, absentEntity);

        assertThat(new AggregateValue(members, "power", AggregateOp.SUM).resolve(ctx()))
                .isEqualTo(new FloatValue(30.5));
        assertThat(new AggregateValue(members, "power", AggregateOp.AVG).resolve(ctx()))
                .isEqualTo(new FloatValue(15.25));
        assertThat(new AggregateValue(members, "power", AggregateOp.MIN).resolve(ctx()))
                .isEqualTo(new FloatValue(10.0));
        assertThat(new AggregateValue(members, "power", AggregateOp.MAX).resolve(ctx()))
                .isEqualTo(new FloatValue(20.5));
        assertThat(new AggregateValue(members, "power", AggregateOp.COUNT).resolve(ctx()))
                .isEqualTo(new IntValue(2));   // only the two numeric members are counted
    }

    @Test
    @DisplayName("AggregateValue over an all-non-numeric selection counts zero (the skip rule applies to every member)")
    void aggregateValue_allNonNumericMembers_countsZero() {
        // e1.label="lamp" non-numeric; e2/e3 have no "label" -> all skipped.
        assertThat(new AggregateValue(Set.of(e1, e2, e3), "label", AggregateOp.COUNT).resolve(ctx()))
                .isEqualTo(new IntValue(0));
    }

    @Test
    @DisplayName("AggregateValue over an empty selection: SUM/COUNT use identities; AVG/MIN/MAX are typed-absent")
    void aggregateValue_emptySelection_definedTotalRule() {
        Set<EntityId> empty = Set.of(absentEntity);   // absent only -> empty effective selection

        assertThat(new AggregateValue(empty, "power", AggregateOp.SUM).resolve(ctx()))
                .isEqualTo(new FloatValue(0.0));
        assertThat(new AggregateValue(empty, "power", AggregateOp.COUNT).resolve(ctx()))
                .isEqualTo(new IntValue(0));
        assertThat(new AggregateValue(empty, "power", AggregateOp.AVG).resolve(ctx()))
                .isInstanceOf(DegradedAttributeValue.class);
        assertThat(new AggregateValue(empty, "power", AggregateOp.MIN).resolve(ctx()))
                .isInstanceOf(DegradedAttributeValue.class);
        assertThat(new AggregateValue(empty, "power", AggregateOp.MAX).resolve(ctx()))
                .isInstanceOf(DegradedAttributeValue.class);
    }

    // ---- Determinism / purity (INV-TO-02, C-SA-2) ---------------------------

    @Test
    @DisplayName("resolution is deterministic — same context (and equal contexts) yield the same value")
    void resolution_isDeterministic() {
        AggregateValue agg = new AggregateValue(Set.of(e1, e2), "power", AggregateOp.AVG);
        ComputedValueContext ctx = ctx();

        assertThat(agg.resolve(ctx)).isEqualTo(agg.resolve(ctx));         // same context
        assertThat(agg.resolve(ctx())).isEqualTo(agg.resolve(ctx()));    // distinct, equal contexts
        assertThat(new AttributeRef(e1, "power").resolve(ctx))
                .isEqualTo(new AttributeRef(e1, "power").resolve(ctx()));
    }

    @Test
    @DisplayName("resolution is pure over the snapshot — no resolver or query-service collaborator (C-SA-2)")
    void resolution_pureOverSnapshot_noIoCollaborator() {
        // The context carries only immutable data (snapshot + time): there is no resolver or
        // query service on it, so resolution against a hand-built snapshot proves the type needs
        // no collaborator and has no I/O capability.
        EntityState only = AutomationTestSupport.state(e1, Availability.AVAILABLE,
                Map.of("power", new IntValue(7)));
        ComputedValueContext ctx = new ComputedValueContext(
                AutomationTestSupport.snapshot(Map.of(e1, only)), AutomationTestSupport.FIXED_INSTANT);

        assertThat(new AttributeRef(e1, "power").resolve(ctx)).isEqualTo(new IntValue(7));
        assertThat(new AggregateValue(Set.of(e1), "power", AggregateOp.SUM).resolve(ctx))
                .isEqualTo(new FloatValue(7.0));
    }
}
