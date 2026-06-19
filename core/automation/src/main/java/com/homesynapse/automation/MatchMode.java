/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Match strategy for a {@link SemanticTagSelector} against an entity's
 * namespaced labels (AMD-89 §2.1).
 *
 * <p>An entity carries tags as {@code namespace:value} strings in its label set
 * (e.g. {@code room:kitchen}, {@code safety:critical}). The match mode decides
 * how a {@code SemanticTagSelector}'s {@code (namespace, value)} pair is tested
 * against those labels:</p>
 *
 * <ul>
 *   <li>{@link #EXACT} — the entity matches when it carries the exact label
 *       {@code namespace:value}.</li>
 *   <li>{@link #NAMESPACE_PREFIX} — the entity matches when it carries any label
 *       in the namespace (any {@code namespace:*}); the selector's {@code value}
 *       is ignored.</li>
 * </ul>
 *
 * <p>This enum is automation-resident and never appears in an event payload, so
 * it carries no wire-format methods (AMD-92 type-residency rule). Lower-case YAML
 * wire forms ({@code exact}, {@code namespace_prefix}) are mapped to these
 * constants at definition-load time.</p>
 *
 * <p>Defined in AMD-89 §2.1.</p>
 *
 * @see SemanticTagSelector
 * @see SelectorResolver
 */
public enum MatchMode {

    /** The entity must carry the exact {@code namespace:value} label. */
    EXACT,

    /** The entity must carry any label in the selector's namespace. */
    NAMESPACE_PREFIX
}
