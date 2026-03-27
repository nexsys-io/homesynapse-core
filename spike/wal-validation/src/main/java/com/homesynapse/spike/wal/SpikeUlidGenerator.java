/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal ULID generator for spike use only — NOT the production UlidFactory.
 *
 * <p>Generates 16-byte (128-bit) ULIDs with a millisecond timestamp prefix (6 bytes)
 * and a random suffix (10 bytes). This generator is neither monotonic nor
 * cryptographically random; {@link ThreadLocalRandom} is sufficient for spike data.
 */
public final class SpikeUlidGenerator {

    private SpikeUlidGenerator() {
        // utility class — no instantiation
    }

    /**
     * Generates a 16-byte ULID: 6 bytes millisecond timestamp + 10 bytes random.
     *
     * @return a new 16-byte array suitable for BLOB(16) insertion
     */
    public static byte[] generate() {
        byte[] ulid = new byte[16];

        // Bytes 0–5: millisecond timestamp (48 bits, big-endian)
        long timestamp = System.currentTimeMillis();
        ulid[0] = (byte) (timestamp >>> 40);
        ulid[1] = (byte) (timestamp >>> 32);
        ulid[2] = (byte) (timestamp >>> 24);
        ulid[3] = (byte) (timestamp >>> 16);
        ulid[4] = (byte) (timestamp >>> 8);
        ulid[5] = (byte) timestamp;

        // Bytes 6–15: random suffix (80 bits)
        byte[] random = new byte[10];
        ThreadLocalRandom.current().nextBytes(random);
        System.arraycopy(random, 0, ulid, 6, 10);

        return ulid;
    }
}
