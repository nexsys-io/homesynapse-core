/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * Manages the encrypted secrets store for sensitive configuration values
 * (Doc 06 §3.4, §8.5; AMD-68).
 *
 * <p>The {@code SecretStore} is used internally during {@code !secret} tag
 * resolution in the loading pipeline. Secrets are stored encrypted on disk
 * using AES-256-GCM and decrypted in memory on demand. The store is created
 * on first use when {@link #set(String, String)} or
 * {@link #setAll(Map)} is called.</p>
 *
 * <p>Since M6.2 the store is encrypted under the {@code "config_secrets"}
 * scope of the shared machine-local key hierarchy ({@link ScopeKeyManager},
 * Doc 15 §7.3) — one key system, not two.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Concurrent reads via {@link #resolve(String)}
 * and {@link #list()} are safe. Write operations ({@link #set(String, String)},
 * {@link #setAll(Map)}, {@link #remove(String)}) are serialized internally.</p>
 *
 * @see SecretEntry
 * @see ScopeKeyManager
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
     * Atomically stores or updates all given secrets in a single
     * all-or-nothing, durable-before-return write (AMD-68 §2.1).
     *
     * <p>Either every entry is persisted and the call returns, or none is
     * persisted and the call throws with the store unchanged — a
     * multi-secret credential set (e.g., an OAuth access+refresh-token
     * pair) can never be torn (AMD-68-INV-01, the AMD-60-INV-03
     * store-layer discharge).</p>
     *
     * <p>Creates the secret store file on first use.</p>
     *
     * @param secrets key→plaintext-value entries to encrypt and persist
     *                atomically; never {@code null}, never empty
     * @throws IllegalArgumentException if {@code secrets} is empty
     */
    void setAll(Map<String, String> secrets);

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

    /**
     * Creates the standard store over {@code ${config_dir}/secrets.enc}
     * (the DEC-M3-16 public-gateway pattern, mirroring
     * {@link ScopeKeyManager#create}).
     *
     * <p>Construction touches no files; the store file — and, through the
     * key manager, the root key and the {@code config_secrets} scope DEK —
     * come into existence on the first mutation (INV-CE-02).</p>
     *
     * @param configDir  the resolved configuration directory, injected as
     *                   a {@code Path} ([AMD-71-A]); never {@code null}
     * @param keyManager the shared-root key manager that owns the
     *                   {@code config_secrets} scope (Doc 15 §7.3);
     *                   never {@code null}
     * @param clock      time source for {@link SecretEntry} timestamps;
     *                   never {@code null}
     * @return a new store; never {@code null}
     */
    static SecretStore create(Path configDir, ScopeKeyManager keyManager,
                              Clock clock) {
        return new StandardSecretStore(configDir, keyManager, clock);
    }
}
