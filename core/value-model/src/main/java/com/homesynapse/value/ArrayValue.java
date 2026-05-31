/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.value;

import java.util.List;

/**
 * An {@link AttributeValue} carrying an ordered, unmodifiable list of element values.
 *
 * <p>Represents list-valued attributes (e.g. Matter list attributes, multi-segment or
 * multi-channel readings). The elements are defensively copied at construction via
 * {@link List#copyOf(java.util.Collection)}, which rejects a {@code null} list and any
 * {@code null} element; an empty list is permitted and the result is unmodifiable.</p>
 *
 * <p><strong>Full-replacement semantics (AMD-47-INV-05).</strong> An {@code ArrayValue}
 * carries no delta or patch semantics: a new {@code ArrayValue} wholly replaces the prior
 * value for an attribute. Delta semantics are rejected because they are incompatible with
 * the bounded-window projection advancer, which reads a bounded event window and cannot
 * reconstruct a value from an unbounded chain of element-level deltas.</p>
 *
 * <p>Element homogeneity (whether all elements share an {@link AttributeValue#attributeType()})
 * is a schema-level concern enforced by the {@link AttributeValidator} against the
 * capability's {@link AttributeSchema}, not by this record. Nesting (an {@code ArrayValue}
 * of {@code ArrayValue}) is permitted by the type but discouraged by schema.</p>
 *
 * <p>Added by AMD-47. Classified by {@link AttributeType#ARRAY}.</p>
 *
 * @param elements the ordered element values; defensively copied to an unmodifiable,
 *                 null-free, possibly-empty list
 * @see AttributeValue
 * @see AttributeType#ARRAY
 * @since 1.0
 */
public record ArrayValue(List<AttributeValue> elements) implements AttributeValue {

    /**
     * Defensively copies the elements to an unmodifiable list.
     *
     * @throws NullPointerException if {@code elements} is {@code null} or contains a
     *         {@code null} element
     */
    public ArrayValue {
        elements = List.copyOf(elements);
    }

    /**
     * Returns the unmodifiable list of element values.
     *
     * @return the elements, never {@code null}, possibly empty
     */
    @Override
    public Object rawValue() {
        return elements;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.ARRAY;
    }
}
