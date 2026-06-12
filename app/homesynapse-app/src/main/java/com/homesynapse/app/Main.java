/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.config.ScopeCipherResult;
import com.homesynapse.config.ScopeKeyManager;
import com.homesynapse.persistence.EncryptedPayload;
import com.homesynapse.persistence.PayloadCipher;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Application entry point for HomeSynapse Core.
 *
 * <p>This class wires all subsystem modules together and manages the
 * application lifecycle. Implementation is deferred to Phase 3.
 */
public final class Main {

    private Main() {
        // Entry point only — no instantiation
    }

    public static void main(String[] args) {
        System.out.println("HomeSynapse Core not yet implemented");
    }

    /**
     * Builds the M6.2 E2 bridge (Doc 15 §3.8 / CARRY 1): constructs the
     * config-resident {@link ScopeKeyManager} and wraps it in a thin
     * adapter of the persistence-defined {@link PayloadCipher} seam.
     *
     * <p>{@code com.homesynapse.app} is the only module that requires both
     * {@code com.homesynapse.config} and {@code com.homesynapse.persistence},
     * so this adapter closes the key-management/encryption cycle with zero
     * new module edges (the AMD-45 injection-at-the-composition-root
     * discipline). The adapter is a field-for-field copy — the two result
     * records are deliberately shape-identical.</p>
     *
     * <p>The full bootstrap wiring — passing this cipher into the
     * five-argument {@code HomeSynapseCore} constructor from
     * {@link #main(String[])} — lands with the app-bootstrap milestone;
     * {@code main()} does not yet construct the runtime. Package-private so
     * the app-level bridge round-trip test exercises the real adapter.</p>
     *
     * @param configDir the resolved configuration directory the key files
     *                  live under; never {@code null}
     * @param clock     time source for scope-key creation stamps; never
     *                  {@code null}
     * @return the adapter, ready to inject into {@code HomeSynapseCore};
     *         never {@code null}
     */
    static PayloadCipher payloadCipher(Path configDir, Clock clock) {
        ScopeKeyManager keyManager = ScopeKeyManager.create(configDir, clock);
        return new PayloadCipher() {
            @Override
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext) {
                ScopeCipherResult result = keyManager.encrypt(scopeId, plaintext);
                return new EncryptedPayload(
                        result.ciphertext(), result.iv(), result.keyVersion());
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion,
                                  byte[] ciphertext, byte[] iv) {
                return keyManager.decrypt(scopeId, keyVersion, ciphertext, iv);
            }
        };
    }
}
