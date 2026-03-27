/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CausalContext} — verifies the 2-field causality record,
 * static factories, and null constraints.
 */
@DisplayName("CausalContext")
class CausalContextTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);
    private static final Ulid ULID_B = new Ulid(0x0191B3C4D5E6F709L, 0x0123456789ABCDEFL);

    // ── Field count verification ─────────────────────────────────────────

    @Test
    @DisplayName("record has exactly 2 components (no traceDepth)")
    void exactlyTwoFields() {
        assertThat(CausalContext.class.getRecordComponents()).hasSize(2);
    }

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("root context — correlationId non-null, causationId null")
        void rootContext() {
            var ctx = new CausalContext(ULID_A, null);

            assertThat(ctx.correlationId()).isEqualTo(ULID_A);
            assertThat(ctx.causationId()).isNull();
        }

        @Test
        @DisplayName("derived context — both fields non-null")
        void derivedContext() {
            var ctx = new CausalContext(ULID_A, ULID_B);

            assertThat(ctx.correlationId()).isEqualTo(ULID_A);
            assertThat(ctx.causationId()).isEqualTo(ULID_B);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null correlationId throws NullPointerException")
        void nullCorrelationId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CausalContext(null, ULID_B))
                    .withMessageContaining("correlationId");
        }

        @Test
        @DisplayName("null causationId accepted (root event)")
        void nullCausationIdAccepted() {
            var ctx = new CausalContext(ULID_A, null);
            assertThat(ctx.causationId()).isNull();
        }
    }

    // ── Factory methods ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("root(correlationId) creates context with null causationId")
        void rootFactory() {
            var ctx = CausalContext.root(ULID_A);

            assertThat(ctx.correlationId()).isEqualTo(ULID_A);
            assertThat(ctx.causationId()).isNull();
            assertThat(ctx.isRoot()).isTrue();
        }

        @Test
        @DisplayName("root(null) throws NullPointerException")
        void rootNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    CausalContext.root(null));
        }

        @Test
        @DisplayName("chain(correlationId, causationId) creates derived context")
        void chainFactory() {
            var ctx = CausalContext.chain(ULID_A, ULID_B);

            assertThat(ctx.correlationId()).isEqualTo(ULID_A);
            assertThat(ctx.causationId()).isEqualTo(ULID_B);
            assertThat(ctx.isRoot()).isFalse();
        }

        @Test
        @DisplayName("chain with null correlationId throws NullPointerException")
        void chainNullCorrelationThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    CausalContext.chain(null, ULID_B));
        }

        @Test
        @DisplayName("chain with null causationId throws NullPointerException")
        void chainNullCausationThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    CausalContext.chain(ULID_A, null))
                    .withMessageContaining("causationId");
        }
    }

    // ── isRoot ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRoot()")
    class IsRootTests {

        @Test
        @DisplayName("returns true when causationId is null")
        void isRootTrue() {
            var ctx = CausalContext.root(ULID_A);
            assertThat(ctx.isRoot()).isTrue();
        }

        @Test
        @DisplayName("returns false when causationId is non-null")
        void isRootFalse() {
            var ctx = CausalContext.chain(ULID_A, ULID_B);
            assertThat(ctx.isRoot()).isFalse();
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical contexts are equal")
        void identicalEqual() {
            var a = CausalContext.root(ULID_A);
            var b = CausalContext.root(ULID_A);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("contexts with different correlationId are not equal")
        void differentCorrelation() {
            var a = CausalContext.root(ULID_A);
            var b = CausalContext.root(ULID_B);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("root vs derived with same correlationId are not equal")
        void rootVsDerived() {
            var root = CausalContext.root(ULID_A);
            var derived = CausalContext.chain(ULID_A, ULID_B);
            assertThat(root).isNotEqualTo(derived);
        }
    }
}
