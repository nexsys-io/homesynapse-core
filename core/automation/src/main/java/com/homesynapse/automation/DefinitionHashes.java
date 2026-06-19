/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Package-private SHA-256 hashing of automation definitions and individual triggers,
 * for replay verification ({@code automation_triggered.definitionHash}, Doc 07 §3.7)
 * and hot-reload duration-timer reconciliation (per-trigger hash comparison, §3.7).
 *
 * <p>The hash is taken over the record's canonical {@code toString()} form, which is a
 * deterministic function of the record's components — equal definitions hash equally,
 * and any component change yields a different hash. SHA-256 is in the JDK, so no
 * external dependency is needed.</p>
 */
final class DefinitionHashes {

    private DefinitionHashes() {
        // Utility class — non-instantiable.
    }

    /** SHA-256 hex of a whole automation definition. */
    static String forDefinition(AutomationDefinition definition) {
        return sha256Hex(definition.toString());
    }

    /** SHA-256 hex of a single trigger definition (for per-index reload comparison). */
    static String forTrigger(TriggerDefinition trigger) {
        return sha256Hex(trigger.toString());
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the Java platform; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
