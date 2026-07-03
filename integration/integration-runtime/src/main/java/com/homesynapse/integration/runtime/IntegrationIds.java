/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * M9.1-interim deterministic integration identity (Settled DP-6).
 *
 * <p>{@link #deriveStable(String)} derives an {@link IntegrationId} from the
 * first 128 bits of the SHA-256 of {@code "homesynapse:integration:" +
 * integrationType}. The derivation is <strong>stable across restarts</strong>
 * — a boot-random id would orphan every {@code Device.integrationId} row on
 * restart, breaking command routing for every adopted device.</p>
 *
 * <p><strong>Documented LTD-04 deviation.</strong> The derived value is
 * deliberately NOT a time-ordered ULID from {@code UlidFactory.generate()} —
 * it is a hash squeezed into the {@link Ulid} carrier, so its timestamp bits
 * are meaningless. The typed-wrapper discipline is preserved
 * ({@link IntegrationId} everywhere; raw {@code Ulid} never escapes).
 * <strong>[Design point] DP-B</strong> (M9-authoring-lane return): Nick rules
 * the durable integration-identity story before M9.2's real device adoption
 * makes this one-way. The supervisor resolves every factory's id through this
 * utility at registration, so a future migration has a single seam.</p>
 */
public final class IntegrationIds {

    private static final String NAMESPACE = "homesynapse:integration:";
    private static final int ULID_BYTES = 16;

    private IntegrationIds() {
        // Static derivation utility — no instantiation.
    }

    /**
     * Derives the stable {@link IntegrationId} for an integration type.
     *
     * @param integrationType the descriptor's integration type (e.g. {@code "zigbee"});
     *                        never {@code null} or blank
     * @return the deterministic id for that type; never {@code null}
     * @throws IllegalArgumentException if {@code integrationType} is blank
     */
    public static IntegrationId deriveStable(String integrationType) {
        Objects.requireNonNull(integrationType, "integrationType");
        if (integrationType.isBlank()) {
            throw new IllegalArgumentException("integrationType must not be blank");
        }
        byte[] hash = sha256(NAMESPACE + integrationType);
        return IntegrationId.of(Ulid.fromBytes(Arrays.copyOf(hash, ULID_BYTES)));
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // Every conformant JRE ships SHA-256 (java.security requirement).
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", impossible);
        }
    }
}
