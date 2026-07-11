/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PortIdentity} value-record tests: validation, nullability, and the
 * strings/ints-only shape (D-M92-1 — no jSerialComm type participates).
 */
class PortIdentityTest {

    private static final String BY_ID =
            "/dev/serial/by-id/usb-SONOFF_SONOFF_Dongle_Plus_MG24_0ae2-if00-port0";

    @Test
    @DisplayName("carries VID:PID, the stable path, and the probe fingerprint")
    void construction_carriesFields() {
        PortIdentity identity = new PortIdentity(0x10C4, 0xEA60, BY_ID, "7.4.5.0");

        assertThat(identity.vendorId()).isEqualTo(0x10C4);
        assertThat(identity.productId()).isEqualTo(0xEA60);
        assertThat(identity.stableId()).isEqualTo(BY_ID);
        assertThat(identity.probeFingerprint()).isEqualTo("7.4.5.0");
    }

    @Test
    @DisplayName("the probe fingerprint is nullable before first contact")
    void fingerprint_nullableBeforeContact() {
        PortIdentity identity = new PortIdentity(0x10C4, 0xEA60, BY_ID, null);

        assertThat(identity.probeFingerprint()).isNull();
    }

    @Test
    @DisplayName("vendor and product ids must be 16-bit; stableId must be present")
    void construction_validation() {
        assertThatThrownBy(() -> new PortIdentity(-1, 0xEA60, BY_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortIdentity(0x10C4, 0x1_0000, BY_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortIdentity(0x10C4, 0xEA60, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("value semantics: equal fields mean equal identities")
    void valueSemantics() {
        assertThat(new PortIdentity(0x10C4, 0xEA60, BY_ID, "7.4.5.0"))
                .isEqualTo(new PortIdentity(0x10C4, 0xEA60, BY_ID, "7.4.5.0"));
    }

    @Test
    @DisplayName("isPinnedOnly: 0/0 is the sanctioned pinned-only sentinel — any "
            + "real USB id in either half is NOT pinned-only (M9.6-RO DP-3)")
    void isPinnedOnly_truthTable() {
        // USB VID 0x0000 is reserved and never assigned to a vendor, so 0/0
        // cannot collide with a real device identity.
        assertThat(new PortIdentity(0, 0, "/dev/zigbee", null).isPinnedOnly())
                .isTrue();
        assertThat(new PortIdentity(0x10C4, 0xEA60, BY_ID, null).isPinnedOnly())
                .isFalse();
        assertThat(new PortIdentity(0, 0xEA60, BY_ID, null).isPinnedOnly())
                .isFalse();
        assertThat(new PortIdentity(0x10C4, 0, BY_ID, null).isPinnedOnly())
                .isFalse();
    }
}
