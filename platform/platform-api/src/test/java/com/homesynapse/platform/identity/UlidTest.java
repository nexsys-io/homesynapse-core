/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for the {@link Ulid} value type.
 *
 * <p>Covers Crockford Base32 encoding/decoding, binary serialization,
 * timestamp extraction, validation, unsigned comparison, and record semantics.</p>
 */
@DisplayName("Ulid")
class UlidTest {

    // -----------------------------------------------------------------------
    // Reference vector: a known ULID with pre-computed representations
    // Timestamp: 2026-01-15T12:00:00.000Z = 1768472400000 ms
    // msb = (1768472400000 << 16) | 0xABCD = 0x19BD2B93D800ABCD
    // lsb = 0x0123456789ABCDEF
    // -----------------------------------------------------------------------
    private static final long REF_TIMESTAMP_MS = 1768472400000L;
    private static final long REF_MSB = (REF_TIMESTAMP_MS << 16) | 0xABCDL;
    private static final long REF_LSB = 0x0123456789ABCDEFL;
    private static final Ulid REF_ULID = new Ulid(REF_MSB, REF_LSB);

    private static final Ulid ZERO_ULID = new Ulid(0L, 0L);

    @Nested
    @DisplayName("Construction and field access")
    class ConstructionTests {

        @Test
        @DisplayName("record components msb and lsb are accessible")
        void recordComponentsAccessible() {
            assertThat(REF_ULID.msb()).isEqualTo(REF_MSB);
            assertThat(REF_ULID.lsb()).isEqualTo(REF_LSB);
        }

        @Test
        @DisplayName("two ULIDs with same msb/lsb are equal")
        void equalUlids() {
            Ulid a = new Ulid(REF_MSB, REF_LSB);
            Ulid b = new Ulid(REF_MSB, REF_LSB);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("two ULIDs with different values are not equal")
        void unequalUlids() {
            Ulid a = new Ulid(1L, 2L);
            Ulid b = new Ulid(1L, 3L);
            assertThat(a).isNotEqualTo(b);

            Ulid c = new Ulid(2L, 2L);
            assertThat(a).isNotEqualTo(c);
        }
    }

    @Nested
    @DisplayName("toString (Crockford Base32 encoding)")
    class ToStringTests {

        @Test
        @DisplayName("all-zero ULID encodes to 26 zeros")
        void allZeroUlid() {
            assertThat(ZERO_ULID.toString()).isEqualTo("00000000000000000000000000");
        }

        @Test
        @DisplayName("output is always exactly 26 characters")
        void alwaysTwentySixChars() {
            assertThat(REF_ULID.toString()).hasSize(26);
            assertThat(ZERO_ULID.toString()).hasSize(26);
            assertThat(new Ulid(-1L, -1L).toString()).hasSize(26);
        }

        @Test
        @DisplayName("output contains only valid Crockford Base32 characters")
        void onlyValidChars() {
            String encoded = REF_ULID.toString();
            String validChars = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
            for (char c : encoded.toCharArray()) {
                assertThat(validChars).contains(String.valueOf(c));
            }
        }

        @Test
        @DisplayName("round-trip: parse(ulid.toString()) equals original")
        void roundTrip() {
            assertThat(Ulid.parse(REF_ULID.toString())).isEqualTo(REF_ULID);
            assertThat(Ulid.parse(ZERO_ULID.toString())).isEqualTo(ZERO_ULID);
        }

        @Test
        @DisplayName("max ULID encodes to 7ZZZZZZZZZZZZZZZZZZZZZZZZZ")
        void maxUlid() {
            Ulid max = new Ulid(-1L, -1L);
            assertThat(max.toString()).isEqualTo("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
        }

        @Test
        @DisplayName("known reference vector encodes deterministically")
        void deterministicEncoding() {
            String first = REF_ULID.toString();
            String second = REF_ULID.toString();
            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("parse (Crockford Base32 decoding)")
    class ParseTests {

        @Test
        @DisplayName("valid 26-char string decodes to correct msb/lsb")
        void validParse() {
            String encoded = REF_ULID.toString();
            Ulid parsed = Ulid.parse(encoded);
            assertThat(parsed.msb()).isEqualTo(REF_MSB);
            assertThat(parsed.lsb()).isEqualTo(REF_LSB);
        }

        @Test
        @DisplayName("case insensitive: lowercase input works")
        void caseInsensitive() {
            String upper = REF_ULID.toString();
            String lower = upper.toLowerCase();
            assertThat(Ulid.parse(lower)).isEqualTo(REF_ULID);
        }

        @Test
        @DisplayName("Crockford decode: I and i map to 1")
        void iMapsToOne() {
            // Build a string with 'I' where '1' would be valid
            String withOne = "01000000000000000000000000";
            Ulid expected = Ulid.parse(withOne);
            String withI = "0I000000000000000000000000";
            assertThat(Ulid.parse(withI)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Crockford decode: L and l map to 1")
        void lMapsToOne() {
            String withOne = "01000000000000000000000000";
            Ulid expected = Ulid.parse(withOne);
            String withL = "0L000000000000000000000000";
            assertThat(Ulid.parse(withL)).isEqualTo(expected);
            String withLower = "0l000000000000000000000000";
            assertThat(Ulid.parse(withLower)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Crockford decode: O and o map to 0")
        void oMapsToZero() {
            String withZero = "00000000000000000000000000";
            Ulid expected = Ulid.parse(withZero);
            String withO = "O0000000000000000000000000";
            assertThat(Ulid.parse(withO)).isEqualTo(expected);
            String withLower = "o0000000000000000000000000";
            assertThat(Ulid.parse(withLower)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null input throws NullPointerException")
        void nullThrows() {
            assertThatThrownBy(() -> Ulid.parse(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("wrong length throws IllegalArgumentException")
        void wrongLengthThrows() {
            assertThatThrownBy(() -> Ulid.parse("0000000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("26");
        }

        @Test
        @DisplayName("invalid character throws IllegalArgumentException")
        void invalidCharThrows() {
            // 'U' is not valid in Crockford Base32
            assertThatThrownBy(() -> Ulid.parse("0U000000000000000000000000"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("overflow: first char > 7 throws IllegalArgumentException")
        void overflowThrows() {
            // '8' encodes to value 8 which is > 7 for first position
            assertThatThrownBy(() -> Ulid.parse("80000000000000000000000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overflow");
        }

        @Test
        @DisplayName("all-zero string parses to zero ULID")
        void allZeroParse() {
            Ulid parsed = Ulid.parse("00000000000000000000000000");
            assertThat(parsed).isEqualTo(ZERO_ULID);
        }
    }

    @Nested
    @DisplayName("toBytes / fromBytes")
    class ByteSerializationTests {

        @Test
        @DisplayName("round-trip: fromBytes(ulid.toBytes()) equals original")
        void roundTrip() {
            assertThat(Ulid.fromBytes(REF_ULID.toBytes())).isEqualTo(REF_ULID);
            assertThat(Ulid.fromBytes(ZERO_ULID.toBytes())).isEqualTo(ZERO_ULID);
        }

        @Test
        @DisplayName("byte array is exactly 16 bytes")
        void exactlySixteenBytes() {
            assertThat(REF_ULID.toBytes()).hasSize(16);
        }

        @Test
        @DisplayName("big-endian byte order: MSB first")
        void bigEndianOrder() {
            Ulid ulid = new Ulid(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
            byte[] bytes = ulid.toBytes();
            assertThat(bytes[0]).isEqualTo((byte) 0x01);
            assertThat(bytes[1]).isEqualTo((byte) 0x02);
            assertThat(bytes[7]).isEqualTo((byte) 0x08);
            assertThat(bytes[8]).isEqualTo((byte) 0x09);
            assertThat(bytes[15]).isEqualTo((byte) 0x10);
        }

        @Test
        @DisplayName("known ULID produces expected byte array")
        void knownBytes() {
            Ulid ulid = new Ulid(0x0000000000000000L, 0x0000000000000001L);
            byte[] bytes = ulid.toBytes();
            // First 15 bytes are 0, last byte is 1
            for (int i = 0; i < 15; i++) {
                assertThat(bytes[i]).isZero();
            }
            assertThat(bytes[15]).isEqualTo((byte) 1);
        }

        @Test
        @DisplayName("fromBytes null throws NullPointerException")
        void fromBytesNullThrows() {
            assertThatThrownBy(() -> Ulid.fromBytes(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromBytes wrong length throws IllegalArgumentException")
        void fromBytesWrongLengthThrows() {
            assertThatThrownBy(() -> Ulid.fromBytes(new byte[8]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("16");
        }

        @Test
        @DisplayName("round-trip with high-bit-set values")
        void highBitValues() {
            Ulid ulid = new Ulid(0x8000000000000000L, 0xFFFFFFFFFFFFFFFFL);
            assertThat(Ulid.fromBytes(ulid.toBytes())).isEqualTo(ulid);
        }
    }

    @Nested
    @DisplayName("extractTimestamp")
    class ExtractTimestampTests {

        @Test
        @DisplayName("all-zero ULID has timestamp at epoch")
        void zeroTimestamp() {
            assertThat(ZERO_ULID.extractTimestamp()).isEqualTo(Instant.EPOCH);
        }

        @Test
        @DisplayName("known timestamp extracted correctly")
        void knownTimestamp() {
            Instant expected = Instant.ofEpochMilli(REF_TIMESTAMP_MS);
            assertThat(REF_ULID.extractTimestamp()).isEqualTo(expected);
        }

        @Test
        @DisplayName("current-ish timestamp produces reasonable epoch millis")
        void reasonableTimestamp() {
            long now = System.currentTimeMillis();
            Ulid ulid = new Ulid(now << 16, 0L);
            long extracted = ulid.extractTimestamp().toEpochMilli();
            assertThat(extracted).isEqualTo(now);
        }

        @Test
        @DisplayName("random bits in lower 16 of msb do not affect timestamp")
        void randomBitsIgnored() {
            long ts = 1000000L;
            Ulid a = new Ulid((ts << 16) | 0x0000L, 0L);
            Ulid b = new Ulid((ts << 16) | 0xFFFFL, 0L);
            assertThat(a.extractTimestamp()).isEqualTo(b.extractTimestamp());
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValidTests {

        @Test
        @DisplayName("valid 26-char Crockford Base32 string returns true")
        void validString() {
            assertThat(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV")).isTrue();
        }

        @Test
        @DisplayName("null returns false")
        void nullReturnsFalse() {
            assertThat(Ulid.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("wrong length returns false")
        void wrongLengthFalse() {
            assertThat(Ulid.isValid("0000")).isFalse();
            assertThat(Ulid.isValid("000000000000000000000000000")).isFalse();
        }

        @Test
        @DisplayName("invalid characters return false")
        void invalidCharsFalse() {
            // 'U' is invalid in Crockford Base32
            assertThat(Ulid.isValid("0U000000000000000000000000")).isFalse();
        }

        @Test
        @DisplayName("empty string returns false")
        void emptyStringFalse() {
            assertThat(Ulid.isValid("")).isFalse();
        }

        @Test
        @DisplayName("overflow first char > 7 returns false")
        void overflowFalse() {
            assertThat(Ulid.isValid("80000000000000000000000000")).isFalse();
        }

        @Test
        @DisplayName("all-zero string is valid")
        void allZeroValid() {
            assertThat(Ulid.isValid("00000000000000000000000000")).isTrue();
        }

        @Test
        @DisplayName("max valid ULID string is valid")
        void maxValid() {
            assertThat(Ulid.isValid("7ZZZZZZZZZZZZZZZZZZZZZZZZZ")).isTrue();
        }

        @Test
        @DisplayName("Crockford alias chars I, L, O are accepted")
        void aliasCharsAccepted() {
            assertThat(Ulid.isValid("0I000000000000000000000000")).isTrue();
            assertThat(Ulid.isValid("0L000000000000000000000000")).isTrue();
            assertThat(Ulid.isValid("0O000000000000000000000000")).isTrue();
        }

        @Test
        @DisplayName("lowercase is valid")
        void lowercaseValid() {
            assertThat(Ulid.isValid("01arz3ndektsv4rrffq69g5fav")).isTrue();
        }
    }

    @Nested
    @DisplayName("compareTo (unsigned comparison)")
    class CompareToTests {

        @Test
        @DisplayName("higher msb sorts after lower msb")
        void higherMsbSortsAfter() {
            Ulid low = new Ulid(1L, 0L);
            Ulid high = new Ulid(2L, 0L);
            assertThat(low.compareTo(high)).isNegative();
            assertThat(high.compareTo(low)).isPositive();
        }

        @Test
        @DisplayName("same msb, higher lsb sorts after lower lsb")
        void sameMsbHigherLsb() {
            Ulid low = new Ulid(1L, 1L);
            Ulid high = new Ulid(1L, 2L);
            assertThat(low.compareTo(high)).isNegative();
            assertThat(high.compareTo(low)).isPositive();
        }

        @Test
        @DisplayName("CRITICAL: high-bit-set msb sorts AFTER small positive msb (unsigned)")
        void unsignedMsbComparison() {
            // 0x8000000000000000L is negative as signed long but large as unsigned
            Ulid small = new Ulid(1L, 0L);
            Ulid large = new Ulid(0x8000000000000000L, 0L);
            // Unsigned: 0x8000000000000000 > 1, so large > small
            assertThat(small.compareTo(large)).isNegative();
            assertThat(large.compareTo(small)).isPositive();
        }

        @Test
        @DisplayName("CRITICAL: high-bit-set lsb sorts AFTER small positive lsb (unsigned)")
        void unsignedLsbComparison() {
            Ulid small = new Ulid(0L, 1L);
            Ulid large = new Ulid(0L, 0x8000000000000000L);
            assertThat(small.compareTo(large)).isNegative();
            assertThat(large.compareTo(small)).isPositive();
        }

        @Test
        @DisplayName("compareTo == 0 iff equals is true")
        void consistentWithEquals() {
            Ulid a = new Ulid(REF_MSB, REF_LSB);
            Ulid b = new Ulid(REF_MSB, REF_LSB);
            assertThat(a.compareTo(b)).isZero();
            assertThat(a).isEqualTo(b);

            Ulid c = new Ulid(REF_MSB, REF_LSB + 1);
            assertThat(a.compareTo(c)).isNotZero();
            assertThat(a).isNotEqualTo(c);
        }

        @Test
        @DisplayName("equal ULIDs compare to zero")
        void equalUlidsCompareZero() {
            assertThat(ZERO_ULID.compareTo(new Ulid(0L, 0L))).isZero();
        }

        @Test
        @DisplayName("all-negative-one values sort after all zeros (unsigned)")
        void maxSortsAfterMin() {
            Ulid min = new Ulid(0L, 0L);
            Ulid max = new Ulid(-1L, -1L);
            assertThat(min.compareTo(max)).isNegative();
            assertThat(max.compareTo(min)).isPositive();
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("equal ULIDs have same hashCode")
        void equalHashCode() {
            Ulid a = new Ulid(REF_MSB, REF_LSB);
            Ulid b = new Ulid(REF_MSB, REF_LSB);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different ULIDs usually have different hashCode")
        void differentHashCode() {
            Ulid a = new Ulid(1L, 2L);
            Ulid b = new Ulid(3L, 4L);
            // Not guaranteed but extremely likely for distinct inputs
            assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("ULID is not equal to null")
        void notEqualToNull() {
            assertThat(REF_ULID).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("Cross-representation round-trips")
    class RoundTripTests {

        @Test
        @DisplayName("toString → parse → toBytes → fromBytes → toString is identity")
        void fullRoundTrip() {
            String original = REF_ULID.toString();
            Ulid parsed = Ulid.parse(original);
            byte[] bytes = parsed.toBytes();
            Ulid fromBytes = Ulid.fromBytes(bytes);
            assertThat(fromBytes.toString()).isEqualTo(original);
        }

        @Test
        @DisplayName("round-trip preserves identity for max ULID")
        void maxRoundTrip() {
            Ulid max = new Ulid(-1L, -1L);
            String str = max.toString();
            assertThat(Ulid.parse(str)).isEqualTo(max);
            assertThat(Ulid.fromBytes(max.toBytes())).isEqualTo(max);
        }
    }
}
