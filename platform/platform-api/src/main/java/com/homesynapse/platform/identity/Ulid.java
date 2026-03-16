/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable 128-bit ULID (Universally Unique Lexicographically Sortable Identifier) value type.
 *
 * <p>ULIDs combine a 48-bit millisecond timestamp with an 80-bit cryptographically random
 * component, producing identifiers that are globally unique and monotonically sortable by
 * creation time. This is the foundation identity type for all HomeSynapse domain objects
 * per LTD-04.</p>
 *
 * <p>The canonical text encoding is a 26-character Crockford Base32 string. The binary
 * encoding is a 16-byte big-endian array suitable for {@code BLOB(16)} storage in SQLite.</p>
 *
 * <p><strong>Bit layout:</strong></p>
 * <pre>
 *   msb: [timestamp 48 bits][random-high 16 bits]
 *   lsb: [random-low 64 bits]
 * </pre>
 *
 * <p><strong>Crockford Base32 encoding:</strong> 26 characters &times; 5 bits = 130 bits.
 * The first character encodes only the top 3 bits of the 128-bit value (values 0&ndash;7);
 * the remaining 25 characters each encode 5 bits.</p>
 *
 * @param msb the most significant 64 bits (48-bit timestamp in upper bits,
 *            16-bit random in lower bits)
 * @param lsb the least significant 64 bits (lower 64 bits of the 80-bit random component)
 * @see UlidFactory
 * @see <a href="https://github.com/ulid/spec">ULID Specification</a>
 */
public record Ulid(long msb, long lsb) implements Comparable<Ulid> {

    /**
     * Crockford Base32 encoding alphabet (excludes I, L, O, U).
     */
    private static final char[] ENCODE_TABLE =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /**
     * Decoding table: maps ASCII code points (0&ndash;127) to 5-bit values,
     * or {@code -1} for invalid characters.
     */
    private static final byte[] DECODE_TABLE = buildDecodeTable();

    /**
     * Returns the canonical 26-character Crockford Base32 representation.
     *
     * <p>The encoding is always uppercase and deterministic &mdash; the same
     * {@code msb}/{@code lsb} pair always produces the same string.</p>
     *
     * @return the 26-character Crockford Base32 string, never {@code null}
     */
    @Override
    public String toString() {
        char[] chars = new char[26];
        chars[0] = ENCODE_TABLE[(int) ((msb >>> 61) & 0x07)];
        for (int i = 1; i <= 12; i++) {
            chars[i] = ENCODE_TABLE[(int) ((msb >>> (61 - i * 5)) & 0x1F)];
        }
        chars[13] = ENCODE_TABLE[(int) (((msb & 0x1L) << 4) | ((lsb >>> 60) & 0x0FL))];
        for (int i = 14; i <= 25; i++) {
            chars[i] = ENCODE_TABLE[(int) ((lsb >>> (55 - (i - 14) * 5)) & 0x1F)];
        }
        return new String(chars);
    }

    /**
     * Decodes a 26-character Crockford Base32 string into a {@code Ulid}.
     *
     * <p>Parsing is case-insensitive. Per the Crockford specification, the characters
     * {@code I} and {@code L} are decoded as {@code 1}, and {@code O} is decoded as
     * {@code 0}. The character {@code U} is rejected as invalid.</p>
     *
     * @param crockford the 26-character Crockford Base32 encoded ULID string
     * @return the decoded {@code Ulid}, never {@code null}
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if the string length is not 26, contains
     *                                  invalid characters, or the first character
     *                                  encodes a value greater than 7 (overflow)
     */
    public static Ulid parse(String crockford) {
        Objects.requireNonNull(crockford, "ULID string must not be null");
        if (crockford.length() != 26) {
            throw new IllegalArgumentException(
                    "ULID string must be 26 characters, got " + crockford.length());
        }

        int first = decodeChar(crockford.charAt(0), 0);
        if (first > 7) {
            throw new IllegalArgumentException(
                    "ULID overflow: first character encodes value " + first + ", maximum is 7");
        }

        long msb = (long) first << 61;
        for (int i = 1; i <= 12; i++) {
            msb |= (long) decodeChar(crockford.charAt(i), i) << (61 - i * 5);
        }
        int val13 = decodeChar(crockford.charAt(13), 13);
        msb |= (long) (val13 >>> 4);

        long lsb = (long) (val13 & 0x0F) << 60;
        for (int i = 14; i <= 25; i++) {
            lsb |= (long) decodeChar(crockford.charAt(i), i) << (55 - (i - 14) * 5);
        }

        return new Ulid(msb, lsb);
    }

    /**
     * Serializes this ULID to a 16-byte big-endian array for {@code BLOB(16)} persistence.
     *
     * <p>The first 8 bytes encode {@code msb} in big-endian order; the last 8 bytes
     * encode {@code lsb} in big-endian order.</p>
     *
     * @return a new 16-byte array, never {@code null}
     */
    public byte[] toBytes() {
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - i * 8));
            bytes[i + 8] = (byte) (lsb >>> (56 - i * 8));
        }
        return bytes;
    }

    /**
     * Reconstructs a {@code Ulid} from a 16-byte big-endian array.
     *
     * @param bytes a 16-byte array as produced by {@link #toBytes()}
     * @return the reconstructed {@code Ulid}, never {@code null}
     * @throws NullPointerException     if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if {@code bytes} is not exactly 16 bytes
     */
    public static Ulid fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "byte array must not be null");
        if (bytes.length != 16) {
            throw new IllegalArgumentException(
                    "byte array must be 16 bytes, got " + bytes.length);
        }
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFFL);
            lsb = (lsb << 8) | (bytes[i + 8] & 0xFFL);
        }
        return new Ulid(msb, lsb);
    }

    /**
     * Extracts the creation timestamp from the upper 48 bits of {@code msb}.
     *
     * <p>The timestamp represents milliseconds since the Unix epoch (1970-01-01T00:00:00Z).
     * This is the time at which the ULID was generated, with millisecond precision.</p>
     *
     * @return the creation timestamp as an {@link Instant}, never {@code null}
     */
    public Instant extractTimestamp() {
        long timestamp = msb >>> 16;
        return Instant.ofEpochMilli(timestamp);
    }

    /**
     * Compares two ULIDs using unsigned comparison of {@code msb} first, then {@code lsb}.
     *
     * <p>This ordering is consistent with the lexicographic ordering of the Crockford Base32
     * text representation and the chronological ordering of ULID timestamps.</p>
     *
     * @param other the {@code Ulid} to compare against
     * @return a negative integer, zero, or a positive integer as this ULID is less than,
     *         equal to, or greater than the specified ULID
     */
    @Override
    public int compareTo(Ulid other) {
        int cmp = Long.compareUnsigned(this.msb, other.msb);
        if (cmp != 0) {
            return cmp;
        }
        return Long.compareUnsigned(this.lsb, other.lsb);
    }

    /**
     * Tests whether the given string is a valid Crockford Base32 ULID representation.
     *
     * <p>Returns {@code true} if the string is exactly 26 characters long, contains only
     * valid Crockford Base32 characters (including the normalization aliases {@code I},
     * {@code L}, and {@code O}), and the first character encodes a value of at most 7.</p>
     *
     * @param candidate the string to validate, may be {@code null}
     * @return {@code true} if the string is a valid ULID representation
     */
    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != 26) {
            return false;
        }
        for (int i = 0; i < 26; i++) {
            char c = candidate.charAt(i);
            if (c >= DECODE_TABLE.length || DECODE_TABLE[c] < 0) {
                return false;
            }
        }
        return DECODE_TABLE[candidate.charAt(0)] <= 7;
    }

    /**
     * Decodes a single Crockford Base32 character to its 5-bit integer value.
     *
     * @param c        the character to decode
     * @param position the character position in the ULID string (for error messages)
     * @return the decoded 5-bit value (0&ndash;31)
     * @throws IllegalArgumentException if the character is not a valid Crockford Base32 digit
     */
    private static int decodeChar(char c, int position) {
        if (c >= DECODE_TABLE.length || DECODE_TABLE[c] < 0) {
            throw new IllegalArgumentException(
                    "Invalid character '" + c + "' at position " + position);
        }
        return DECODE_TABLE[c];
    }

    /**
     * Builds the Crockford Base32 decoding lookup table.
     *
     * <p>Maps each valid ASCII character to its 5-bit decoded value. Invalid characters
     * are mapped to {@code -1}. Per the Crockford specification: {@code I} and {@code L}
     * decode as {@code 1}, {@code O} decodes as {@code 0}, and {@code U} is invalid.</p>
     */
    private static byte[] buildDecodeTable() {
        byte[] table = new byte[128];
        Arrays.fill(table, (byte) -1);
        for (int i = 0; i < ENCODE_TABLE.length; i++) {
            table[ENCODE_TABLE[i]] = (byte) i;
            if (Character.isLetter(ENCODE_TABLE[i])) {
                table[Character.toLowerCase(ENCODE_TABLE[i])] = (byte) i;
            }
        }
        table['I'] = 1;
        table['i'] = 1;
        table['L'] = 1;
        table['l'] = 1;
        table['O'] = 0;
        table['o'] = 0;
        return table;
    }
}
