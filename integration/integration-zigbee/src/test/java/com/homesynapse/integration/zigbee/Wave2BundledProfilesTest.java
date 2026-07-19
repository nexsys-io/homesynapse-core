/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.ConfirmationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9.7-W2 §1 — the Wave-2 bundled profiles (S31 Lite zb · SNZB-02P · SNZB-04P
 * · SNZB-01P), loaded through the REAL loader + registry. The four entries are
 * DOSSIER-SOURCED pre-silicon (fingerprints from zigpy/zha machine captures;
 * re-derived from OUR interviews at the first bench session): the S31 ships
 * exact_model ONLY (DP-1 — its profileId is a bench-adjudicated conflict), the
 * three sensors ship {@code confirmation: []} (DP-3 — the shipped SNZB-03P
 * empty-means-nothing-issuable precedent), and the S31's on_off block is the
 * platform's first EXACT_MATCH DISCRETE confirmation entry (its timeout is the
 * Hue's SEED, not a measurement — DP-4).
 */
@DisplayName("Wave-2 bundled profiles (M9.7-W2 §1)")
class Wave2BundledProfilesTest {

    /** Wave-2 profile ids — dossier-sourced additions (Wave-1 ids ride {@link MeasuredCorpusValues}). */
    private static final String S31_PROFILE_ID = "sonoff_s31_lite_zb";
    private static final String SNZB_02P_PROFILE_ID = "sonoff_snzb_02p";
    private static final String SNZB_04P_PROFILE_ID = "sonoff_snzb_04p";
    private static final String SNZB_01P_PROFILE_ID = "sonoff_snzb_01p";

    private final ZigbeeProfileLoader loader = new ZigbeeProfileLoader();
    private StandardDeviceProfileRegistry registry;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    Wave2BundledProfilesTest() {
    }

    @BeforeEach
    void setUp() {
        registry = new StandardDeviceProfileRegistry();
        registry.register(loader.loadBundled());
    }

    private DeviceProfile profileById(String profileId) {
        return registry.allProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst().orElseThrow();
    }

    private Fingerprint fingerprintOf(DeviceProfile profile) {
        return profile.matches().stream()
                .filter(Fingerprint.class::isInstance)
                .map(Fingerprint.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the bundle loads with SIX profiles — the deliberate census pin "
            + "(a seventh entry is a deliberate edit here, never a drive-by)")
    void bundleLoadsWithSixProfiles() {
        assertThat(loader.loadBundled()).hasSize(6);
        // allProfiles() materializes EVERY body (full-parse contract) — a
        // malformed Wave-2 entry fails here, never as a silent partial load.
        assertThat(registry.allProfiles()).hasSize(6);
    }

    @Test
    @DisplayName("the S31 resolves by exact model — (\"SONOFF\", \"S31 Lite zb\"), "
            + "spaces and casing exact")
    void s31ResolvesByExactModel() {
        assertThat(registry.findProfile("SONOFF", "S31 Lite zb"))
                .map(DeviceProfile::profileId)
                .contains(S31_PROFILE_ID);
    }

    @Test
    @DisplayName("the SNZB-02P resolves by exact model — (\"eWeLink\", \"SNZB-02P\")")
    void snzb02pResolves() {
        assertThat(registry.findProfile("eWeLink", "SNZB-02P"))
                .map(DeviceProfile::profileId)
                .contains(SNZB_02P_PROFILE_ID);
    }

    @Test
    @DisplayName("the SNZB-04P resolves by exact model — (\"eWeLink\", \"SNZB-04P\")")
    void snzb04pResolves() {
        assertThat(registry.findProfile("eWeLink", "SNZB-04P"))
                .map(DeviceProfile::profileId)
                .contains(SNZB_04P_PROFILE_ID);
    }

    @Test
    @DisplayName("the SNZB-01P resolves by exact model — (\"eWeLink\", \"SNZB-01P\")")
    void snzb01pResolves() {
        assertThat(registry.findProfile("eWeLink", "SNZB-01P"))
                .map(DeviceProfile::profileId)
                .contains(SNZB_01P_PROFILE_ID);
    }

    @Test
    @DisplayName("the S31's confirmation block is exact — ONE on_off entry: "
            + "EXACT_MATCH · OnOff/0x0000 · VERIFIED_REPORTS · ON_CHANGE · "
            + "CONFIRMABLE · 5000 ms · the 3 degrade rules (the Hue-SEED values)")
    void s31ConfirmationExact() {
        DeviceProfile s31 = profileById(S31_PROFILE_ID);

        assertThat(s31.confirmation()).hasSize(1);
        ConfirmationCharacterization confirm = s31.confirmation().get(0);
        assertThat(confirm.capability()).isEqualTo("on_off");
        assertThat(confirm.confirmationMode()).isEqualTo(ConfirmationMode.EXACT_MATCH);
        assertThat(confirm.authoritativeAttribute()).isEqualTo("OnOff/0x0000");
        assertThat(confirm.reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
        assertThat(confirm.reportingPosture()).isEqualTo(ReportingPosture.ON_CHANGE);
        assertThat(confirm.confirmability()).isEqualTo(Confirmability.CONFIRMABLE);
        assertThat(confirm.recommendedTimeoutMs()).isEqualTo(5000L);
        assertThat(confirm.degradeRule()).containsExactlyInAnyOrder(
                DegradeRule.NO_REPORT_TIMEOUT_TO_UNCONFIRMED,
                DegradeRule.NACK_TO_FAILED,
                DegradeRule.CONFIRM_FROM_CACHE_OR_READBACK);
        // DP-4: the 5000 ms is the Hue's SEED, not a measurement — the honesty
        // marker lives in the notes; the bench mints the real envelope.
        assertThat(confirm.notes()).contains("SEED value, not measured");
    }

    @Test
    @DisplayName("the three sensors carry confirmation: [] — non-null AND empty "
            + "(DP-3, the shipped SNZB-03P empty-means-nothing-issuable precedent)")
    void sensorsCarryEmptyConfirmation() {
        for (String profileId : List.of(SNZB_02P_PROFILE_ID, SNZB_04P_PROFILE_ID,
                SNZB_01P_PROFILE_ID)) {
            DeviceProfile profile = profileById(profileId);
            assertThat(profile.confirmation())
                    .as("profile %s carries the EMPTY block, never null/absent",
                            profileId)
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("DP-1: the S31 carries exact_model ONLY — its fingerprint is "
            + "WITHHELD pending the 0xC05E-vs-0x0104 bench adjudication; a "
            + "future add is a deliberate edit of this pin")
    void s31CarriesNoFingerprint() {
        DeviceProfile s31 = profileById(S31_PROFILE_ID);

        assertThat(s31.matches()).hasSize(1);
        assertThat(s31.matches().iterator().next()).isInstanceOf(ExactModel.class);
    }

    @Test
    @DisplayName("DP-2: the 04P fingerprint carries 0xFC57 — the zha machine "
            + "capture leads over the quirk comment's 0xFC37 (dossier §6.3-g)")
    void snzb04pFingerprintCarriesFc57() {
        Fingerprint fingerprint = fingerprintOf(profileById(SNZB_04P_PROFILE_ID));

        assertThat(fingerprint.endpoints()).hasSize(1);
        assertThat(fingerprint.endpoints().get(0).inClusters())
                .contains(0xFC57)
                .doesNotContain(0xFC37);
    }

    @Test
    @DisplayName("the 01P fingerprint carries 0xFC57 but NOT 0xFC11 — one custom "
            + "cluster, unlike its 02P/04P siblings")
    void snzb01pFingerprintOmitsFc11() {
        Fingerprint fingerprint = fingerprintOf(profileById(SNZB_01P_PROFILE_ID));

        assertThat(fingerprint.endpoints()).hasSize(1);
        assertThat(fingerprint.endpoints().get(0).inClusters())
                .contains(0xFC57)
                .doesNotContain(0xFC11);
    }

    @Test
    @DisplayName("the disambiguation traps resolve NOTHING — the SNZB-04PR2 "
            + "successor and the WB01 predecessor are distinct on-wire identities")
    void predecessorTrapsResolveNothing() {
        assertThat(registry.findProfile("eWeLink", "SNZB-04PR2")).isEmpty();
        assertThat(registry.findProfile("eWeLink", "WB01")).isEmpty();
    }

    @Test
    @DisplayName("the Wave-1 profiles still resolve — the additive-entries regression")
    void wave1ProfilesUnbroken() {
        assertThat(registry.findProfile(MeasuredCorpusValues.HUE_MANUFACTURER,
                MeasuredCorpusValues.HUE_MODEL))
                .map(DeviceProfile::profileId)
                .contains(MeasuredCorpusValues.HUE_PROFILE_ID);
        assertThat(registry.findProfile(MeasuredCorpusValues.SNZB_MANUFACTURER,
                MeasuredCorpusValues.SNZB_MODEL))
                .map(DeviceProfile::profileId)
                .contains(MeasuredCorpusValues.SNZB_PROFILE_ID);
    }
}
