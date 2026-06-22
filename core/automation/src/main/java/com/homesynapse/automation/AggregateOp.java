/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * The bounded fold operators an {@link AggregateValue} applies over a resolved entity set's
 * numeric snapshot attribute values (Doc 16 §3.2). Deliberately small (the minimal V1 set);
 * no operator reads I/O or carries state.
 *
 * <p>{@link #SUM} and {@link #COUNT} have an identity element (0) and so are defined over the
 * empty effective selection; {@link #AVG}, {@link #MIN}, and {@link #MAX} are undefined over
 * an empty selection and resolve to the typed-absent sentinel (see {@link AggregateValue}).</p>
 *
 * @see AggregateValue
 */
enum AggregateOp {

    /** Sum of the numeric members; the empty selection yields {@code 0}. */
    SUM,

    /** Arithmetic mean of the numeric members; undefined over the empty selection. */
    AVG,

    /** Smallest numeric member; undefined over the empty selection. */
    MIN,

    /** Largest numeric member; undefined over the empty selection. */
    MAX,

    /** Count of members that contributed a numeric value; the empty selection yields {@code 0}. */
    COUNT
}
