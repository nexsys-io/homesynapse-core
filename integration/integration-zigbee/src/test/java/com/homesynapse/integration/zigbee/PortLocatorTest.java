/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PortLocator} tests (AMD-96/E2, INV-CE-04): VID:PID matching, by-id-path
 * preference, reopen-by-stable-id resolution — and the explicit proof that USB
 * descriptor strings NEVER participate: a SONOFF-branded string and a
 * {@code Silicon_Labs_CP2102N} string are both ignored.
 *
 * <p>M9.6-RO adds the pinned-only reopen rule: a {@link PortIdentity#isPinnedOnly()}
 * identity matches by CANONICALIZED PATH EQUALITY ONLY — Tier 2 (VID:PID class)
 * is skipped and there is NO class-constant fallback, so an operator pin can never
 * reopen a neighboring same-class stick. Healthy identities flow through the two
 * pre-existing tiers byte-unchanged (the throwing-canonicalizer proof below).
 */
class PortLocatorTest {

    private static final String SONOFF_BY_ID =
            "/dev/serial/by-id/usb-SONOFF_SONOFF_Dongle_Plus_MG24_0ae2-if00-port0";

    /** The bench's alias-pinned config value (udev symlink; enumerates as nothing). */
    private static final String ALIAS = "/dev/zigbee";

    /** A pure-map canonicalizer: mapped keys resolve, everything else is itself. */
    private static UnaryOperator<String> mapCanonicalizer(Map<String, String> links) {
        return path -> links.getOrDefault(path, path);
    }

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

    // ── M9.6-RO: the pinned-only reopen rule (DP-2) ─────────────────────────

    @Test
    @DisplayName("pinned-only reopen matches by canonicalized path equality — the "
            + "alias resolves to the enumerated device node")
    void reopen_pinnedOnly_matchesByCanonicalizedPath() {
        PortIdentity pinned = new PortIdentity(0, 0, ALIAS, "EZSP");
        PortCandidate real = new PortCandidate(
                "/dev/ttyUSB0", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(real),
                mapCanonicalizer(Map.of(ALIAS, "/dev/ttyUSB0")));

        assertThat(locator.reopenTarget(pinned)).contains(real);
    }

    @Test
    @DisplayName("TWO STICKS SAFETY: a pinned-only identity NEVER class-falls-back "
            + "to a neighboring coordinator-class stick (DP-2, no Tier 2)")
    void reopen_pinnedOnly_twoSticks_neverTheNeighbor() {
        PortIdentity pinned = new PortIdentity(0, 0, ALIAS, "EZSP");
        // The neighbor IS coordinator-class (0x10C4/0xEA60) on a different path —
        // exactly what Tier 2 would have opened; the pinned rule must not.
        PortCandidate neighbor = new PortCandidate("/dev/ttyUSB1",
                "/dev/serial/by-id/usb-ITEAD_ZBDongle-P_5cf2-if00-port0",
                0x10C4, 0xEA60, null);

        // The pin resolves to a device node the neighbor does not occupy…
        PortLocator resolving = new PortLocator(() -> List.of(neighbor),
                mapCanonicalizer(Map.of(ALIAS, "/dev/ttyUSB0")));
        assertThat(resolving.reopenTarget(pinned)).isEmpty();

        // …and an unresolvable pin must be exactly as strict.
        PortLocator unresolving = new PortLocator(() -> List.of(neighbor),
                mapCanonicalizer(Map.of()));
        assertThat(unresolving.reopenTarget(pinned)).isEmpty();
    }

    @Test
    @DisplayName("identity canonicalizer (the 1-arg constructor): pinned-only "
            + "matching degrades to raw string equality — today's semantics")
    void reopen_pinnedOnly_identityCanonicalizer_rawStringSemantics() {
        // A pin that IS the real device node still matches literally…
        PortIdentity directPin = new PortIdentity(0, 0, "/dev/ttyUSB0", "EZSP");
        PortCandidate node = new PortCandidate(
                "/dev/ttyUSB0", null, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(node));
        assertThat(locator.reopenTarget(directPin)).contains(node);

        // …while an unresolved alias matches nothing (and never the class).
        PortIdentity aliasPin = new PortIdentity(0, 0, ALIAS, "EZSP");
        assertThat(locator.reopenTarget(aliasPin)).isEmpty();
    }

    @Test
    @DisplayName("a by-id-pinned pinned-only identity matches through the "
            + "byIdPath disjunct — the raw-fallback window where systemPath "
            + "equality cannot (M9.6-RO-R1)")
    void reopen_pinnedOnly_byIdPin_matchesViaByIdPathDisjunct() {
        // An operator pins the by-id path itself and the capture still fell to
        // the pinned-only sentinel (the node was unenumerable or unidentified
        // at bind). Under raw-string matching (the 1-arg locator — exactly the
        // production toRealPath-fallback window) the pinned path equals NO
        // candidate systemPath — only the byIdPath disjunct of the pinned-only
        // rule can re-find the stick.
        PortIdentity byIdPin = new PortIdentity(0, 0, SONOFF_BY_ID, "EZSP");
        PortCandidate candidate = new PortCandidate(
                "/dev/ttyUSB2", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(candidate));

        assertThat(locator.reopenTarget(byIdPin)).contains(candidate);
    }

    @Test
    @DisplayName("DEVNUM-IMMUNITY PIN: a healthy identity re-matches a "
            + "string-identical candidate — devnum is unrepresented BY DESIGN")
    void reopen_devnumImmunity_pin() {
        // The field scenario: the stick re-attaches with a new USB devnum; every
        // FIELD the model carries (paths, VID:PID) is string-identical. No model
        // field can distinguish the re-attached stick — so reopen MUST match.
        PortCandidate original = new PortCandidate(
                "/dev/ttyUSB0", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        PortIdentity identity = PortLocator.identityFor(original, "EZSP");
        PortCandidate reattached = new PortCandidate(
                "/dev/ttyUSB0", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        PortLocator locator = new PortLocator(() -> List.of(reattached));

        assertThat(locator.reopenTarget(identity)).contains(reattached);

        // The same property without a by-id path (Tier 1's systemPath leg).
        PortCandidate bareOriginal = new PortCandidate(
                "/dev/ttyUSB0", null, 0x10C4, 0xEA60, null);
        PortIdentity bareIdentity = PortLocator.identityFor(bareOriginal, "EZSP");
        PortLocator bareLocator = new PortLocator(() -> List.of(
                new PortCandidate("/dev/ttyUSB0", null, 0x10C4, 0xEA60, null)));
        assertThat(bareLocator.reopenTarget(bareIdentity))
                .contains(bareOriginal);
    }

    @Test
    @DisplayName("healthy identities NEVER consult the canonicalizer — the two "
            + "pre-existing tiers run byte-unchanged")
    void reopen_healthyIdentity_neverConsultsCanonicalizer() {
        UnaryOperator<String> forbidden = path -> {
            throw new AssertionError(
                    "the canonicalizer was consulted for a healthy identity");
        };
        PortIdentity healthy =
                new PortIdentity(0x10C4, 0xEA60, SONOFF_BY_ID, "7.4.5.0");

        // Tier 1: stable-id equality.
        PortCandidate byId = new PortCandidate(
                "/dev/ttyUSB1", SONOFF_BY_ID, 0x10C4, 0xEA60, null);
        assertThat(new PortLocator(() -> List.of(byId), forbidden)
                .reopenTarget(healthy)).contains(byId);

        // Tier 2: VID:PID class fallback.
        PortCandidate rehosted = new PortCandidate(
                "/dev/ttyUSB3", null, 0x10C4, 0xEA60, null);
        assertThat(new PortLocator(() -> List.of(rehosted), forbidden)
                .reopenTarget(healthy)).contains(rehosted);
    }

    // ── M9.6-RO: the production canonicalizer binding (DP-4) ────────────────

    @Test
    @DisplayName("realPathCanonicalizer resolves an existing path and falls back "
            + "to the RAW string on an unresolvable one (total, never throws)")
    void realPathCanonicalizer_resolvesAndFallsBack(@TempDir Path tempDir)
            throws Exception {
        UnaryOperator<String> canonicalizer = PortLocator.realPathCanonicalizer();

        // An existing file resolves to a real path naming the same file.
        Path existing = Files.createFile(tempDir.resolve("port-node"));
        String resolved = canonicalizer.apply(existing.toString());
        assertThat(Files.isSameFile(Path.of(resolved), existing)).isTrue();

        // A path that resolves to nothing comes back RAW — matching then
        // degrades to string equality (today's semantics), never an exception.
        String missing = tempDir.resolve("no-such-node").toString();
        assertThat(canonicalizer.apply(missing)).isEqualTo(missing);
    }
}
