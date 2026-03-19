/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Set;

/**
 * Manages the encrypted secrets store for sensitive configuration values
 * (Doc 06 §3.4, §8.5).
 *
 * <p>The {@code SecretStore} is used internally during {@code !secret} tag
 * resolution in the loading pipeline. Secrets are stored encrypted on disk
 * using AES-256-GCM and decrypted in memory on demand. The store is created
 * on first use when {@link #set(String, String)} is called.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Concurrent reads via {@link #resolve(String)}
 * and {@link #list()} are safe. Write operations ({@link #set(String, String)},
 * {@link #remove(String)}) are serialized internally.</p>
 *
 * @see SecretEntry
 * @see ConfigurationService
 */
public interface SecretStore {

    /**
     * Returns the decrypted value for the given secret key.
     *
     * @param key the secret key to resolve; never {@code null}
     * @return the decrypted secret value; never {@code null}
     * @throws IllegalArgumentException if the key does not exist in the
     *         secret store
     */
    String resolve(String key);

    /**
     * Stores or updates a secret value. Creates the secret store file on
     * first use.
     *
     * @param key   the secret key; never {@code null}
     * @param value the plaintext secret value to encrypt and store;
     *              never {@code null}
     */
    void set(String key, String value);

    /**
     * Removes a secret by key.
     *
     * @param key the secret key to remove; never {@code null}
     * @throws IllegalArgumentException if the key does not exist in the
     *         secret store
     */
    void remove(String key);

    /**
     * Returns the set of all secret key names in the store.
     *
     * <p>The returned set is unmodifiable. The values themselves are not
     * included — only the key names.</p>
     *
     * @return an unmodifiable set of secret key names; never {@code null}
     */
    Set<String> list();
}
