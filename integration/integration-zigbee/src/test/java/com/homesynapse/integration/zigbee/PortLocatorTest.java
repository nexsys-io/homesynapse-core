/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PortLocator} tests (AMD-96/E2, INV-CE-04): VID:PID matching, by-id-path
 * preference, reopen-by-stable-id resolution — and the explicit proof that USB
 * descriptor strings NEVER participate: a SONOFF-branded string and a
 * {@code Silicon_Labs_CP2102N} string are both ignored.
 */
class PortLocatorTest {

    private static final String SONOFF_BY_ID =
            "/dev/serial/by-id/usb-SONOFF_SONOFF_Dongle_Plus_MG24_0ae2-if00-port0";

    @Test
    @DisplayName("locates by VID:PID 10c4:ea60 — descriptor strings do not "
            + "participate in either direction")
    void locate_matchesVidPid_descriptorsIgnored() {
        // The FTDI device WEARS the Silicon Labs descriptor string; the real
        // coordinator wears a SONOFF string. Descriptor-string matching would pick
        // the wrong port — VID:PID matching picks the right one.
        PortCandidate wrongIdRightString = new PortCandidate(
                "/dev/ttyUSB0", null, 0x0403, 0x6001,
                "Silicon_Labs_CP2102N_USB_to_UART_Bridge_Controller");
        PortCandidate rightIdSonoffString = new PortCandidate(
                "/dev/ttyUSB1", SONOFF_BY_ID, 0x10C4, 0xEA60,
                "SONOFF Dongle Plus MG24");
        PortLocator locator = new PortLocator(
                () -> List.of(wrongIdRightString, rightIdSonoffString));

        Optional<PortCandidate> located = locator.locate();

        assertThat(located).contains(rightIdSonoffString);
    }

    @Test
    @DisplayName("prefers the candidate with a stable by-id path")
    void locate_prefersByIdPath() {
        PortCandidate withoutById = new PortCandidate(
                "/dev/ttyUSB0", null, 0x10C4, 0xEA60, "SONOFF Dongle Plus MG24");
        PortCandidate withById = new PortCandidate(
                "/dev/ttyUSB1", SONOFF_BY_ID, 0x10C4, 0xEA60,
                "SONOFF Dongle Plus MG24");
        PortLocator locator = new PortLocator(
                () -> List.of(withoutById, withById));

        assertThat(locator.locate()).contains(withById);
    }

    @Test
    @DisplayName("no coordinator-class port present: empty")
    void locate_none() {
        PortCandidate other = new PortCandidate(
                "/dev/ttyACM0", null, 0x2341, 0x0043, "Arduino Uno");
        PortLocator locator = new PortLocator(() -> List.of(other));

        assertThat(locator.locate()).isEmpty();
    }

    @Test
    @DisplayName("reopen resolves by the stable by-id path even after the device "
            + "node renumbered")
    void reopen_byStableId_afterRenumbering() {
        PortIdentity identity =
                new PortIdentity(0x10C4, 0xEA60, SONOFF_BY_ID, "7.4.5.0");
        // Renumbered: ttyUSB0 → ttyUSB1; the by-id path is stable.
        PortCandidate renumbered = new PortCandidate(
                "/dev/ttyUSB1", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(renumbered));

        assertThat(locator.reopenTarget(identity)).contains(renumbered);
    }

    @Test
    @DisplayName("reopen falls back to the VID:PID class when the by-id path is gone")
    void reopen_fallsBackToVidPid() {
        PortIdentity identity =
                new PortIdentity(0x10C4, 0xEA60, SONOFF_BY_ID, "7.4.5.0");
        PortCandidate rehosted = new PortCandidate(
                "/dev/ttyUSB3", null, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(rehosted));

        assertThat(locator.reopenTarget(identity)).contains(rehosted);
    }

    @Test
    @DisplayName("reopen yields empty while the device is absent")
    void reopen_absent() {
        PortIdentity identity =
                new PortIdentity(0x10C4, 0xEA60, SONOFF_BY_ID, null);
        PortLocator locator = new PortLocator(List::of);

        assertThat(locator.reopenTarget(identity)).isEmpty();
    }

    @Test
    @DisplayName("identityFor prefers the by-id path as the stable id")
    void identityFor_prefersByIdPath() {
        PortCandidate candidate = new PortCandidate(
                "/dev/ttyUSB0", SONOFF_BY_ID, 0x10C4, 0xEA60, "SONOFF");

        PortIdentity identity = PortLocator.identityFor(candidate, "7.4.5.0");

        assertThat(identity.stableId()).isEqualTo(SONOFF_BY_ID);
        assertThat(identity.probeFingerprint()).isEqualTo("7.4.5.0");

        PortCandidate noById = new PortCandidate(
                "/dev/ttyUSB0", null, 0x10C4, 0xEA60, null);
        assertThat(PortLocator.identityFor(noById, null).stableId())
                .isEqualTo("/dev/ttyUSB0");
    }
}
