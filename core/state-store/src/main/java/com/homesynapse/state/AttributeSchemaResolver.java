/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeSchema;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow, read-only schema seam: resolves an {@code attributeKey} to its declared
 * {@link AttributeSchema} so the typed change-detection comparator (AMD-51) can reconstruct
 * an inbound {@code state_reported} value to its declared variant (AMD-51-INV-05 / DP-K).
 *
 * <p>The production resolver is a pure {@code Map::get} lookup over an
 * <strong>immutable compile-time snapshot</strong> built at the composition root from
 * {@code StandardCapabilities.attributeSchemas()}. This is the {@code QuantityValue.CATALOGUE}
 * determinism posture — injected immutable config, NOT a live mutable-registry read — so the
 * derivation rule that consults it stays a pure function of its inputs plus injected config
 * (AMD-50-INV-03).</p>
 *
 * <p>Keyed by {@code attributeKey} only, not {@code (EntityId, attributeKey)}: there is no
 * entity→capability binding yet, and the standard catalogue's {@code attributeKey →
 * AttributeType} mapping is globally consistent ({@code StandardCapabilities.attributeSchemas()}
 * fails fast on a conflicting-type collision). Entity-aware resolution lands with the future
 * entity→capability binding work unit.</p>
 *
 * @see AttributeValueReconstructor
 * @since 1.0
 */
public interface AttributeSchemaResolver {

    /**
     * Resolves the schema for the given attribute key.
     *
     * @param attributeKey the reported attribute key; never {@code null}
     * @return the schema, or {@link Optional#empty()} if no schema is known for the key
     */
    Optional<AttributeSchema> resolve(String attributeKey);

    /**
     * Returns a resolver backed by an immutable snapshot of the given schema map.
     *
     * <p>The map is defensively copied; subsequent mutation of the argument does not affect
     * the resolver. Lookup is a pure {@code Map::get}.</p>
     *
     * @param schemas the {@code attributeKey → schema} map; never {@code null}, values
     *                never {@code null}
     * @return an immutable resolver over a snapshot of {@code schemas}
     */
    static AttributeSchemaResolver of(Map<String, AttributeSchema> schemas) {
        Objects.requireNonNull(schemas, "schemas must not be null");
        Map<String, AttributeSchema> snapshot = Map.copyOf(schemas);
        return key -> Optional.ofNullable(snapshot.get(key));
    }

    /**
     * Returns a resolver that knows no schemas — every {@link #resolve} returns
     * {@link Optional#empty()}.
     *
     * <p>Used by the string-semantics {@code DerivationRule.production()} gateway: with no
     * schema, reconstruction falls back to {@code StringValue} on both operands, so the
     * comparator does an exact string compare (the pre-typed behaviour). Distinct from a
     * typed resolver only in that no key ever resolves.</p>
     *
     * @return an empty resolver; never {@code null}
     */
    static AttributeSchemaResolver empty() {
        return key -> Optional.empty();
    }
}
