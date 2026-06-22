/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.AttributeValue;

/**
 * A {@link ComputedValue} that resolves to one entity's attribute value read from the
 * trigger-time snapshot (Doc 16 §3.2): {@code ctx.snapshot().states().get(entity)
 * .attributes().get(attribute)}.
 *
 * <p><strong>Typed-absent rule (total, never throws — C-SA-2).</strong> If the entity is not
 * in the snapshot, or the attribute has no value, resolution returns the typed-absent
 * sentinel ({@link ComputedValues#absent}) rather than throwing — absence is a valid typed
 * input, not an error. The rule is pinned in {@code ComputedValueTest}.</p>
 *
 * @param entity    the entity whose attribute to read, never {@code null}
 * @param attribute the attribute key to read, never {@code null}
 * @see ComputedValue
 */
record AttributeRef(EntityId entity, String attribute) implements ComputedValue {

    /**
     * Validates both fields are present.
     *
     * @throws NullPointerException if {@code entity} or {@code attribute} is {@code null}
     */
    AttributeRef {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
    }

    @Override
    public AttributeValue resolve(ComputedValueContext ctx) {
        var state = ctx.snapshot().states().get(entity);
        if (state == null) {
            return ComputedValues.absent("entity absent from snapshot: " + entity);
        }
        AttributeValue value = state.attributes().get(attribute);
        return value != null ? value : ComputedValues.absent("attribute absent: " + attribute);
    }
}
