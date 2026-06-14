/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A faithful, durable-counter test implementation of {@link PayloadCipher} for
 * {@code AtRestEncryptionWritePathTest}.
 *
 * <p>The persistence module has no {@code config} dependency (main or test), so
 * the production {@code StandardScopeKeyManager.encryptPayload} cannot be wired
 * into a persistence-module test. This double models the production contract
 * structurally:</p>
 *
 * <ul>
 *   <li><strong>Real AES-256-GCM</strong> with a deterministic per-{@code
 *       (scope, keyVersion)} key (SHA-256 of {@code scope:version}) so
 *       ciphertext genuinely differs from plaintext and round-trips, and so a
 *       version bump uses a genuinely different DEK.</li>
 *   <li><strong>Counter nonces</strong>: a strictly-monotonic per-{@code
 *       (scope, keyVersion)} counter, encoded big-endian in the trailing 8
 *       bytes of the 12-byte nonce (the DP-C construction).</li>
 *   <li><strong>Durable ahead of return</strong>: the high-water mark is
 *       written to a backing file (flushed) <em>before</em> the nonce is
 *       returned, and a freshly constructed instance over the same file
 *       re-initializes from the persisted maxima — modelling OR-M6-NONCE's
 *       crash/restart re-init-from-durable-state property at the write-path
 *       level.</li>
 *   <li><strong>{@link #rotate()}</strong> bumps the key version to model a
 *       restore-time DEK rotation (DP-D) — a fresh DEK whose nonces cannot
 *       collide with the prior version's under one key.</li>
 * </ul>
 *
 * <p>The <em>production</em> counter durability (fsync, re-init from the
 * persisted max) is proven against the real manager in the config module's
 * {@code ScopeKeyManagerPayloadNonceTest}; this double proves the persistence
 * write path consumes such a cipher correctly and never re-stores a nonce
 * across a restart.</p>
 */
final class CountingPayloadCipher implements PayloadCipher {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Path counterFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    private int keyVersion = 1;

    CountingPayloadCipher(Path counterFile) {
        this.counterFile = Objects.requireNonNull(counterFile, "counterFile");
    }

    /** Simulates a restore-time DEK rotation (DP-D): the next writes use a new key version. */
    void rotate() {
        lock.lock();
        try {
            keyVersion++;
        } finally {
            lock.unlock();
        }
    }

    int currentKeyVersion() {
        lock.lock();
        try {
            return keyVersion;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EncryptedPayload encrypt(String scopeId, byte[] plaintext) {
        int version;
        byte[] nonce;
        lock.lock();
        try {
            version = keyVersion;
            long counter = nextCounterDurable(scopeId, version);
            nonce = nonceBytes(counter);
        } finally {
            lock.unlock();
        }
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek(scopeId, version), nonce, plaintext);
        return new EncryptedPayload(ciphertext, nonce, version);
    }

    @Override
    public byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext, byte[] iv) {
        return gcm(Cipher.DECRYPT_MODE, dek(scopeId, keyVersion), iv, ciphertext);
    }

    /**
     * Allocates the next counter for {@code (scope, version)}, persisting the
     * new high-water mark to the backing file before returning it.
     */
    private long nextCounterDurable(String scopeId, int version) {
        Map<String, Long> counters = load();
        String key = scopeId + "@" + version;
        long next = counters.getOrDefault(key, 0L) + 1L;
        counters.put(key, next);
        store(counters);
        return next;
    }

    private Map<String, Long> load() {
        if (!Files.exists(counterFile)) {
            return new TreeMap<>();
        }
        try {
            return mapper.readValue(Files.readAllBytes(counterFile),
                    mapper.getTypeFactory().constructMapType(
                            TreeMap.class, String.class, Long.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void store(Map<String, Long> counters) {
        try {
            // Files.write flushes to the OS; a freshly constructed cipher over
            // the same file reads the persisted maxima (the restart property).
            Files.write(counterFile, mapper.writeValueAsBytes(counters));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] nonceBytes(long counter) {
        byte[] nonce = new byte[GCM_IV_BYTES];
        ByteBuffer.wrap(nonce).putLong(GCM_IV_BYTES - Long.BYTES, counter);
        return nonce;
    }

    /** Deterministic per-(scope, version) 256-bit key — distinct versions ⇒ distinct DEKs. */
    private static SecretKeySpec dek(String scopeId, int version) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest((scopeId + ":" + version).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] gcm(int mode, SecretKeySpec key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GCM " + mode + " failed", e);
        }
    }
}
