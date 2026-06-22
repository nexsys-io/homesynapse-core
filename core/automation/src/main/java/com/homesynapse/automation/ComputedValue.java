/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.value.AttributeValue;

/**
 * A bounded, total, side-effect-free typed derivation that resolves to a concrete
 * {@link AttributeValue} at run initiation (Doc 16 §3.2 / §4; C-SA-2). A {@code ComputedValue}
 * occupies an action value position (a {@link CommandAction#parameters()} value) and is
 * resolved against the captured trigger-time snapshot in the run-init path, <em>before</em>
 * the frozen {@link ActionExecutor} runs — so the executor only ever sees a concrete value.
 *
 * <h2>Why a sealed type, not an expression string</h2>
 *
 * <p>Every permit is total over a typed, finite input set (the AMD-03 trigger-time
 * {@link com.homesynapse.state.StateSnapshot} plus the injected resolution time), so
 * resolution is deterministic and replay-safe (INV-TO-02) and carries <strong>no I/O
 * capability on the type</strong> (C-SA-2). This forecloses the silent-failure class an
 * opaque template/expression string would reintroduce (SP1): a {@code ComputedValue} can
 * neither read I/O nor throw on valid typed inputs — absence is a defined, total rule
 * (see {@link AttributeRef} / {@link AggregateValue}), never an exception.</p>
 *
 * <h2>Residency (AMD-92-INV-01)</h2>
 *
 * <p>{@code ComputedValue} is automation-resident — it is package-private and must
 * <strong>never</strong> appear in an event payload. Resolution flattens it to a concrete
 * {@link AttributeValue} before any value could be recorded.</p>
 *
 * <h2>Scope (V1 — minimal / present-not-built-out, Nick 2026-06-22)</h2>
 *
 * <p>The seam plus a small operator set, exercised programmatically. YAML/loader parsing of
 * computed values and the component model are deferred; computed <em>conditions</em> are out
 * (computed <em>values</em> only). The permits are exactly the three below.</p>
 *
 * @see ComputedValueContext
 * @see ComputedValues
 */
sealed interface ComputedValue permits LiteralValue, AttributeRef, AggregateValue {

    /**
     * Resolves this computed value to a concrete {@link AttributeValue} against the run-init
     * context. Total and side-effect-free: it reads only the context's snapshot and time,
     * performs no I/O, and never throws on valid typed inputs (C-SA-2). An absent or
     * non-numeric input resolves to a defined typed-absent sentinel ({@link ComputedValues#absent})
     * or is skipped, never thrown.
     *
     * @param ctx the run-init resolution context (captured snapshot + resolution time),
     *            never {@code null}
     * @return the resolved concrete value, never {@code null}
     */
    AttributeValue resolve(ComputedValueContext ctx);
}
