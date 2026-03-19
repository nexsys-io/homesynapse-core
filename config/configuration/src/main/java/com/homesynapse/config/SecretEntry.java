/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.time.Instant;
import java.util.Objects;

/**
 * Internal representation of a single entry within the decrypted secrets
 * store (Doc 06 §3.4, §4.8).
 *
 * <p>Each {@code SecretEntry} holds a key-value pair along with creation and
 * update timestamps. Entries are stored encrypted on disk using AES-256-GCM
 * and decrypted in memory by the {@link SecretStore} implementation.</p>
 *
 * <p>All fields are non-null. The {@code value} field contains the plaintext
 * secret after decryption.</p>
 *
 * @param key       the secret identifier (e.g., {@code "mqtt_password"});
 *                  never {@code null}
 * @param value     the plaintext secret value after decryption;
 *                  never {@code null}
 * @param createdAt the instant the secret was first stored;
 *                  never {@code null}
 * @param updatedAt the instant the secret was last modified;
 *                  never {@code null}
 *
 * @see SecretStore
 */
public record SecretEntry(
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Validates that all fields are non-null.
     */
    public SecretEntry {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
