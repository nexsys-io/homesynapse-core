/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventDraft;

import java.util.List;

/**
 * Strategy interface for deriving downstream events from inbound envelopes (DEC-M3-10).
 *
 * <p>The {@link StateProjection} delegates derivation to a {@code DerivationRule}
 * during the READ phase of each {@code onEvent} call (AMD-41 §3.2.1). The rule
 * inspects the {@link DerivationContext} (prior entity state + inbound envelope)
 * and returns zero or more {@link EventDraft} instances that the projection will
 * publish during the PUBLISH phase (LIVE mode only).</p>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations MUST NOT call {@code EventPublisher.publish()} — the
 *       projection handles publishing after the rule returns.</li>
 *   <li>Implementations MUST NOT mutate {@code StateStore} — the projection
 *       handles all state mutation in the STATE UPDATE phase.</li>
 *   <li>Implementations MUST be deterministic per INV-PROJ-01: the same
 *       {@code DerivationContext} MUST always produce the same drafts.</li>
 *   <li>The returned list MAY be empty when no derivation applies.</li>
 *   <li>Derived {@code EventDraft.eventTime} SHOULD inherit from the causing
 *       envelope or be {@code null} — never {@code Instant.now()}.</li>
 * </ul>
 *
 * @see DerivationContext
 * @see StateProjection
 */
@FunctionalInterface
public interface DerivationRule {

    /**
     * Evaluates the inbound event against prior state and returns zero or more
     * derived event drafts.
     *
     * @param context the read-only derivation context; never {@code null}
     * @return an immutable or defensively-copied list of derived drafts; may be
     *         empty, never {@code null}
     */
    List<EventDraft> evaluate(DerivationContext context);

    /**
     * Returns the production change-detect rule in <strong>string-compare mode</strong>:
     * on an inbound {@code state_reported} whose value differs (as an exact string) from the
     * entity's prior canonical value, it emits one derived {@code state_changed} draft;
     * otherwise it emits nothing.
     *
     * <p>This builds the typed {@code ProductionDerivationRule} (AMD-51) with the structural
     * comparator, the default float/quantity epsilon, and an <em>empty</em>
     * {@link AttributeSchemaResolver}. With no schema for any key, reconstruction falls back
     * to {@code StringValue} on both operands, so the comparator does an exact string compare
     * — behaviourally identical to the pre-typed (M4.0b-2) rule. This is the fixture/default
     * gateway; the composition root wires the typed rule via
     * {@link #production(AttributeValueComparator, ComparisonPolicy, AttributeSchemaResolver)}.</p>
     *
     * <p>DEC-M3-16 gateway into the package-private {@code ProductionDerivationRule} — mirrors
     * {@link StateCheckpointSource#stub()} and {@link StateQueryService#materialized}. The
     * returned rule is stateless, deterministic (INV-PROJ-01 / AMD-50-INV-03), and safe to
     * share across threads.</p>
     *
     * @return the string-semantics production derivation rule; never {@code null}
     */
    static DerivationRule production() {
        return new ProductionDerivationRule(
                AttributeValueComparator.structural(),
                ComparisonPolicy.FP_NOISE_DEFAULT,
                AttributeSchemaResolver.empty());
    }

    /**
     * Returns the production change-detect rule in <strong>typed mode</strong> (AMD-51 /
     * M4.0b-3): it reconstructs both the inbound {@code state_reported} value and the prior
     * canonical value to their schema-declared {@link com.homesynapse.device.AttributeValue}
     * variant and asks the {@code comparator} whether a {@code state_changed} should be
     * emitted, under {@code policy}. The emitted payload stays String (§2.7).
     *
     * <p>DEC-M3-16 gateway. The composition root wires this with
     * {@link AttributeValueComparator#structural()}, {@link ComparisonPolicy#FP_NOISE_DEFAULT},
     * and an {@link AttributeSchemaResolver} built from the standard capability schemas. The
     * returned rule is stateless (apart from its injected immutable collaborators),
     * deterministic (INV-PROJ-01 / AMD-50-INV-03), and safe to share across threads.</p>
     *
     * @param comparator the typed change-detection comparator; never {@code null}
     * @param policy     the float/quantity comparison tolerances; never {@code null}
     * @param schemas    the schema resolver (immutable snapshot); never {@code null}
     * @return the typed production derivation rule; never {@code null}
     */
    static DerivationRule production(AttributeValueComparator comparator,
                                     ComparisonPolicy policy,
                                     AttributeSchemaResolver schemas) {
        return new ProductionDerivationRule(comparator, policy, schemas);
    }
}
