/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parameterized tests for all 8 typed ID wrapper records in platform-api.
 *
 * <p>Each wrapper is a {@code record(Ulid value) implements Comparable<XxxId>}
 * with factory methods {@code of(Ulid)} and {@code parse(String)}. Since they
 * all follow the identical pattern (delegating to {@link Ulid}), a single
 * parameterized test class covers all 8 types without duplication.</p>
 *
 * <p>These tests verify that delegation is wired correctly — they do NOT
 * re-test Crockford Base32 encoding/decoding (that is Ulid's responsibility,
 * fully verified in {@link UlidTest}).</p>
 */
@DisplayName("Typed ID wrappers")
class TypedIdTest {

    /** A known ULID for deterministic testing. */
    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);

    /** A second ULID that sorts after ULID_A (higher msb). */
    private static final Ulid ULID_B = new Ulid(0x0191B3C4D5E6F709L, 0x0123456789ABCDEFL);

    /**
     * Provides factory functions for all 8 typed ID wrappers.
     *
     * <p>Each entry contains: display name, of(Ulid) factory, parse(String) factory,
     * and a value extractor to retrieve the wrapped Ulid from the typed ID.</p>
     */
    static Stream<Arguments> idTypes() {
        return Stream.of(
                Arguments.of("DeviceId",
                        (Function<Ulid, Object>) DeviceId::of,
                        (Function<String, Object>) DeviceId::parse,
                        (Function<Object, Ulid>) id -> ((DeviceId) id).value()),
                Arguments.of("EntityId",
                        (Function<Ulid, Object>) EntityId::of,
                        (Function<String, Object>) EntityId::parse,
                        (Function<Object, Ulid>) id -> ((EntityId) id).value()),
                Arguments.of("AreaId",
                        (Function<Ulid, Object>) AreaId::of,
                        (Function<String, Object>) AreaId::parse,
                        (Function<Object, Ulid>) id -> ((AreaId) id).value()),
                Arguments.of("IntegrationId",
                        (Function<Ulid, Object>) IntegrationId::of,
                        (Function<String, Object>) IntegrationId::parse,
                        (Function<Object, Ulid>) id -> ((IntegrationId) id).value()),
                Arguments.of("AutomationId",
                        (Function<Ulid, Object>) AutomationId::of,
                        (Function<String, Object>) AutomationId::parse,
                        (Function<Object, Ulid>) id -> ((AutomationId) id).value()),
                Arguments.of("PersonId",
                        (Function<Ulid, Object>) PersonId::of,
                        (Function<String, Object>) PersonId::parse,
                        (Function<Object, Ulid>) id -> ((PersonId) id).value()),
                Arguments.of("HomeId",
                        (Function<Ulid, Object>) HomeId::of,
                        (Function<String, Object>) HomeId::parse,
                        (Function<Object, Ulid>) id -> ((HomeId) id).value()),
                Arguments.of("SystemId",
                        (Function<Ulid, Object>) SystemId::of,
                        (Function<String, Object>) SystemId::parse,
                        (Function<Object, Ulid>) id -> ((SystemId) id).value())
        );
    }

    @Nested
    @DisplayName("Construction via of(Ulid)")
    class OfTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("of(ulid) returns non-null instance")
        void ofReturnsNonNull(String name, Function<Ulid, Object> of,
                              Function<String, Object> parse, Function<Object, Ulid> value) {
            assertThat(of.apply(ULID_A)).isNotNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("of(ulid).value() returns the same Ulid")
        void ofPreservesValue(String name, Function<Ulid, Object> of,
                              Function<String, Object> parse, Function<Object, Ulid> value) {
            Object id = of.apply(ULID_A);
            assertThat(value.apply(id)).isEqualTo(ULID_A);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("of(null) throws NullPointerException")
        void ofNullThrows(String name, Function<Ulid, Object> of,
                          Function<String, Object> parse, Function<Object, Ulid> value) {
            assertThatThrownBy(() -> of.apply(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("parse(String) round-trip")
    class ParseTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("parse(ulid.toString()) equals of(ulid)")
        void parseRoundTrip(String name, Function<Ulid, Object> of,
                            Function<String, Object> parse, Function<Object, Ulid> value) {
            Object fromOf = of.apply(ULID_A);
            Object fromParse = parse.apply(ULID_A.toString());
            assertThat(fromParse).isEqualTo(fromOf);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("parse(null) throws NullPointerException")
        void parseNullThrows(String name, Function<Ulid, Object> of,
                             Function<String, Object> parse, Function<Object, Ulid> value) {
            assertThatThrownBy(() -> parse.apply(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("parse(\"\") throws IllegalArgumentException")
        void parseEmptyThrows(String name, Function<Ulid, Object> of,
                              Function<String, Object> parse, Function<Object, Ulid> value) {
            assertThatThrownBy(() -> parse.apply(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("parse(\"invalid\") throws IllegalArgumentException")
        void parseInvalidThrows(String name, Function<Ulid, Object> of,
                                Function<String, Object> parse, Function<Object, Ulid> value) {
            assertThatThrownBy(() -> parse.apply("invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("compareTo delegation")
    class CompareToTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("lower ULID sorts before higher ULID")
        @SuppressWarnings("unchecked")
        void compareToOrdering(String name, Function<Ulid, Object> of,
                               Function<String, Object> parse, Function<Object, Ulid> value) {
            Comparable<Object> low = (Comparable<Object>) of.apply(ULID_A);
            Object high = of.apply(ULID_B);
            assertThat(low.compareTo(high)).isNegative();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("compareTo == 0 iff equals is true")
        @SuppressWarnings("unchecked")
        void compareToConsistentWithEquals(String name, Function<Ulid, Object> of,
                                           Function<String, Object> parse,
                                           Function<Object, Ulid> value) {
            Object a = of.apply(ULID_A);
            Object b = of.apply(ULID_A);
            assertThat(((Comparable<Object>) a).compareTo(b)).isZero();
            assertThat(a).isEqualTo(b);

            Object c = of.apply(ULID_B);
            assertThat(((Comparable<Object>) a).compareTo(c)).isNotZero();
            assertThat(a).isNotEqualTo(c);
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("same Ulid produces equal typed IDs")
        void sameUlidEquals(String name, Function<Ulid, Object> of,
                            Function<String, Object> parse, Function<Object, Ulid> value) {
            Object a = of.apply(ULID_A);
            Object b = of.apply(ULID_A);
            assertThat(a).isEqualTo(b);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("different Ulids produce unequal typed IDs")
        void differentUlidsNotEqual(String name, Function<Ulid, Object> of,
                                    Function<String, Object> parse, Function<Object, Ulid> value) {
            Object a = of.apply(ULID_A);
            Object b = of.apply(ULID_B);
            assertThat(a).isNotEqualTo(b);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("equal typed IDs have same hashCode")
        void equalHashCode(String name, Function<Ulid, Object> of,
                           Function<String, Object> parse, Function<Object, Ulid> value) {
            Object a = of.apply(ULID_A);
            Object b = of.apply(ULID_A);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    @DisplayName("toString delegation")
    class ToStringTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.homesynapse.platform.identity.TypedIdTest#idTypes")
        @DisplayName("toString() returns Crockford Base32 of wrapped Ulid")
        void toStringDelegates(String name, Function<Ulid, Object> of,
                               Function<String, Object> parse, Function<Object, Ulid> value) {
            Object id = of.apply(ULID_A);
            assertThat(id.toString()).isEqualTo(ULID_A.toString());
        }
    }

    /**
     * Type safety verification — compile-time, documented in comments.
     *
     * <p>The whole point of typed ID wrappers is that DeviceId and EntityId
     * are NOT interchangeable despite both wrapping Ulid. A DeviceId cannot be
     * passed where an EntityId is expected, and vice versa. This is enforced by
     * the Java type system at compile time — no runtime test is needed or possible.</p>
     *
     * <p>For example, the following would not compile:</p>
     * <pre>{@code
     *   DeviceId deviceId = DeviceId.of(ulid);
     *   EntityId entityId = deviceId;  // COMPILE ERROR: incompatible types
     *   void accept(EntityId id) { }
     *   accept(deviceId);              // COMPILE ERROR: incompatible types
     * }</pre>
     */
    @Nested
    @DisplayName("Type safety")
    class TypeSafetyTests {

        @Test
        @DisplayName("DeviceId and EntityId wrapping same Ulid are not equal")
        void crossTypeInequality() {
            // Same Ulid value, different wrapper types — must not be equal
            DeviceId deviceId = DeviceId.of(ULID_A);
            EntityId entityId = EntityId.of(ULID_A);
            assertThat(deviceId).isNotEqualTo(entityId);
        }

        @Test
        @DisplayName("all 8 wrapper types are distinct despite wrapping same Ulid")
        void allTypesDistinct() {
            Object[] ids = {
                    DeviceId.of(ULID_A),
                    EntityId.of(ULID_A),
                    AreaId.of(ULID_A),
                    IntegrationId.of(ULID_A),
                    AutomationId.of(ULID_A),
                    PersonId.of(ULID_A),
                    HomeId.of(ULID_A),
                    SystemId.of(ULID_A)
            };
            // Every pair should be unequal
            for (int i = 0; i < ids.length; i++) {
                for (int j = i + 1; j < ids.length; j++) {
                    assertThat(ids[i])
                            .as("%s should not equal %s",
                                    ids[i].getClass().getSimpleName(),
                                    ids[j].getClass().getSimpleName())
                            .isNotEqualTo(ids[j]);
                }
            }
        }
    }
}
