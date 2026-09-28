/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LOCK-1: the integration id the store carries for every adopted device is pinned at its bytes
 * (Settled DP-6). T1 pins the id's text form; T1b pins the derivation's input — the first 16
 * bytes of the SHA-256 of the namespace plus the type — both as the test computes it and as the
 * derived id carries it, so a namespace change (T1b red) and a change in how those bytes are
 * squeezed into the {@code Ulid} carrier or rendered (T1 red, T1b green) are told apart.
 *
 * <p>Both values were computed at {@code 1f1d1e0}; the id is the one the stored device rows
 * carry. Change a pinned value only through a WU that names the migration or re-derivation it
 * performs. No clock is read.</p>
 */
@DisplayName("IntegrationIds -- the derived integration id pinned at its bytes (LOCK-1)")
final class IntegrationIdsPinTest {

    private static final String INTEGRATION_TYPE = "zigbee";

    /** {@code IntegrationIds.deriveStable("zigbee").toString()} at {@code 1f1d1e0}. */
    private static final String PINNED_ID = "6V1CMGY2HKF4H1FGZ4H7F257FS";

    /** The first 16 bytes of the SHA-256 of the namespace plus the type, lowercase hex. */
    private static final String PINNED_INPUT_HEX = "db0b290f0a33792217c3e489de229df9";

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    IntegrationIdsPinTest() {
    }

    @Test
    @DisplayName("T1: the derived id's text form is the pinned literal")
    void deriveStable_textForm_isPinned() {
        assertThat(IntegrationIds.deriveStable(INTEGRATION_TYPE).toString())
                .as("a changed derivation orphans every adopted device's Device.integrationId row "
                        + "on the next boot")
                .isEqualTo(PINNED_ID);
    }

    @Test
    @DisplayName("T1b: the derivation's input -- the first 16 bytes of the namespace hash -- is pinned")
    void derivationInput_first16Bytes_arePinned() throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(("homesynapse:integration:" + INTEGRATION_TYPE)
                        .getBytes(StandardCharsets.UTF_8));
        byte[] carried = IntegrationIds.deriveStable(INTEGRATION_TYPE).value().toBytes();

        // Soft, so a moved pin reports both hex values in one run.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(HexFormat.of().formatHex(Arrays.copyOf(hash, 16)))
                    .as("the namespace hash's first 16 bytes moved: every integration id "
                            + "re-derives and every adopted device's Device.integrationId row "
                            + "is orphaned on the next boot")
                    .isEqualTo(PINNED_INPUT_HEX);
            softly.assertThat(HexFormat.of().formatHex(carried))
                    .as("the derived id no longer carries the namespace hash's first 16 bytes: "
                            + "the derivation's input moved and every adopted device's "
                            + "Device.integrationId row is orphaned on the next boot")
                    .isEqualTo(PINNED_INPUT_HEX);
        });
    }
}
