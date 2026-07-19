/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZigbeeProfileLoader} tests: the loader-owned {@code schemaVersion}
 * discipline (§H — unknown major fail-closed, unknown minor tolerated-additive),
 * the namespace-collision rule (§C — duplicate ids within a load are a loader
 * error), the index-first shape (§F — bodies parse lazily, on match), the
 * fail-closed {@code degradeRule} vocabulary (F-15 — an unknown rule fails that
 * profile, naming field + profileId), and the bundled-corpus round-trip including
 * the AMD-97 {@code confirmation[]} block.
 */
class ZigbeeProfileLoaderTest {

    private final ZigbeeProfileLoader loader = new ZigbeeProfileLoader();

    private static String wrap(String profilesJson) {
        return """
                {
                  "schemaVersion": {"major": 1, "minor": 0},
                  "profiles": [%s]
                }
                """.formatted(profilesJson);
    }

    private static final String MINIMAL_PROFILE = """
            {
              "profileId": "%s",
              "matches": [{"type": "exact_model",
                           "manufacturer": "eWeLink", "model": "SNZB-03P"}],
              "category": "STANDARD_ZCL"
            }
            """;

    @Nested
    @DisplayName("schemaVersion (§H — loader-owned, forward-only)")
    class SchemaVersion {

        @Test
        @DisplayName("unknown MAJOR is a fail-closed loader error")
        void unknownMajorFailsClosed() {
            String json = """
                    {"schemaVersion": {"major": 2, "minor": 0}, "profiles": []}
                    """;

            assertThatThrownBy(() ->
                    loader.parse(json, ProfileSource.BUNDLED))
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("major");
        }

        @Test
        @DisplayName("unknown MINOR is tolerated-additive")
        void unknownMinorTolerated() {
            String json = """
                    {"schemaVersion": {"major": 1, "minor": 99}, "profiles": []}
                    """;

            assertThat(loader.parse(json, ProfileSource.BUNDLED)).isEmpty();
        }

        @Test
        @DisplayName("a missing schemaVersion is a fail-closed loader error")
        void missingSchemaVersionFailsClosed() {
            assertThatThrownBy(() ->
                    loader.parse("{\"profiles\": []}", ProfileSource.BUNDLED))
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("schemaVersion");
        }
    }

    @Nested
    @DisplayName("namespace + collision rules (§C)")
    class NamespaceRules {

        @Test
        @DisplayName("duplicate profile ids within one load are a loader error")
        void duplicateIdsWithinLoadFail() {
            String json = wrap(MINIMAL_PROFILE.formatted("sonoff_snzb_03p")
                    + "," + MINIMAL_PROFILE.formatted("sonoff_snzb_03p"));

            assertThatThrownBy(() -> loader.parse(json, ProfileSource.USER))
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("sonoff_snzb_03p");
        }

        @Test
        @DisplayName("bare (first-party) and dotted (third-party) ids never merge")
        void distinctNamespacesNeverMerge() {
            String json = wrap(MINIMAL_PROFILE.formatted("energy-meter")
                    + "," + MINIMAL_PROFILE.formatted("acme.energy-meter"));

            List<ProfileEntry> entries = loader.parse(json, ProfileSource.USER);

            assertThat(entries).extracting(ProfileEntry::profileId)
                    .containsExactly("energy-meter", "acme.energy-meter");
        }
    }

    @Nested
    @DisplayName("index-first load (§F — match before full-parse)")
    class IndexFirst {

        @Test
        @DisplayName("a malformed profile BODY does not fail the load; it fails on materialization")
        void malformedBodyFailsLazily() {
            String good = MINIMAL_PROFILE.formatted("good_profile");
            String badBody = """
                    {
                      "profileId": "bad_profile",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "Acme", "model": "X1"}],
                      "category": "NOT_A_REAL_CATEGORY"
                    }
                    """;

            List<ProfileEntry> entries =
                    loader.parse(wrap(good + "," + badBody), ProfileSource.USER);

            assertThat(entries).hasSize(2);
            ProfileEntry goodEntry = entries.get(0);
            ProfileEntry badEntry = entries.get(1);
            assertThat(goodEntry.materialize().profileId()).isEqualTo("good_profile");
            assertThatThrownBy(badEntry::materialize)
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("bad_profile");
        }

        @Test
        @DisplayName("criteria and priority are indexed eagerly")
        void criteriaIndexedEagerly() {
            String json = wrap("""
                    {
                      "profileId": "ikea_tradfri_family",
                      "priority": 7,
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "IKEA of Sweden",
                                   "modelPrefix": "TRADFRI"}],
                      "category": "MINOR_QUIRKS"
                    }
                    """);

            ProfileEntry entry = loader.parse(json, ProfileSource.USER).get(0);

            assertThat(entry.priority()).isEqualTo(7);
            assertThat(entry.criteria()).hasSize(1);
            assertThat(entry.criteria().iterator().next())
                    .isInstanceOf(ModelWildcard.class);
        }

        @Test
        @DisplayName("an unknown match-criteria type is an eager loader error")
        void unknownCriteriaTypeFails() {
            String json = wrap("""
                    {
                      "profileId": "p1",
                      "matches": [{"type": "telepathy", "manufacturer": "A", "model": "B"}],
                      "category": "STANDARD_ZCL"
                    }
                    """);

            assertThatThrownBy(() -> loader.parse(json, ProfileSource.USER))
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("telepathy");
        }
    }

    @Nested
    @DisplayName("confirmation degradeRule vocabulary (F-15 — unknown values fail closed)")
    class DegradeRuleVocabulary {

        private String confirmationProfile(String profileId, String degradeRulesJson) {
            return """
                    {
                      "profileId": "%s",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "Acme", "model": "X1"}],
                      "category": "STANDARD_ZCL",
                      "confirmation": [
                        {
                          "capability": "on_off",
                          "confirmationMode": "EXACT_MATCH",
                          "authoritativeAttribute": "OnOff/0x0000",
                          "reportsAuthoritative": "VERIFIED_REPORTS",
                          "reportingPosture": "ON_CHANGE",
                          "confirmability": "CONFIRMABLE",
                          "recommendedTimeoutMs": 5000,
                          "degradeRule": [%s]
                        }
                      ]
                    }
                    """.formatted(profileId, degradeRulesJson);
        }

        @Test
        @DisplayName("an unknown degradeRule value fails THAT profile closed, naming field + profileId")
        void unknownDegradeRuleFailsClosed() {
            String json = wrap(confirmationProfile("bad_degrade_profile",
                    "\"NO_REPORT_TIMEOUT_TO_UNCONFIRMED\", \"RETRY_UNTIL_HEARD\""));

            ProfileEntry entry = loader.parse(json, ProfileSource.USER).get(0);

            assertThatThrownBy(entry::materialize)
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("degradeRule")
                    .hasMessageContaining("bad_degrade_profile")
                    .hasMessageContaining("RETRY_UNTIL_HEARD");
        }

        @Test
        @DisplayName("sibling profiles in the same document still load (per-profile granularity, the §F shape)")
        void siblingProfilesStillLoad() {
            String json = wrap(confirmationProfile("bad_degrade_profile",
                    "\"RETRY_UNTIL_HEARD\"")
                    + "," + MINIMAL_PROFILE.formatted("healthy_sibling"));

            List<ProfileEntry> entries = loader.parse(json, ProfileSource.USER);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(1).materialize().profileId())
                    .isEqualTo("healthy_sibling");
            assertThatThrownBy(entries.get(0)::materialize)
                    .isInstanceOf(ProfileLoadException.class)
                    .hasMessageContaining("bad_degrade_profile");
        }

        @Test
        @DisplayName("the ratified four-value vocabulary round-trips unchanged")
        void ratifiedVocabularyRoundTrips() {
            String json = wrap(confirmationProfile("known_rules_profile",
                    "\"NO_REPORT_TIMEOUT_TO_UNCONFIRMED\", \"NACK_TO_FAILED\", "
                            + "\"IMMEDIATE_UNCONFIRMED\", "
                            + "\"CONFIRM_FROM_CACHE_OR_READBACK\""));

            DeviceProfile profile =
                    loader.parse(json, ProfileSource.USER).get(0).materialize();

            assertThat(profile.confirmation().get(0).degradeRule())
                    .containsExactlyInAnyOrder(DegradeRule.values());
        }
    }

    @Nested
    @DisplayName("bundled corpus round-trip (the measured Wave-1 values)")
    class BundledCorpus {

        @Test
        @DisplayName("the bundled resource loads the Wave-1 pair + the four "
                + "dossier-sourced Wave-2 profiles (M9.7-W2 §1)")
        void bundledResourceLoads() {
            List<ProfileEntry> entries = loader.loadBundled();

            assertThat(entries).extracting(ProfileEntry::profileId)
                    .containsExactlyInAnyOrder(
                            MeasuredCorpusValues.HUE_PROFILE_ID,
                            MeasuredCorpusValues.SNZB_PROFILE_ID,
                            "sonoff_s31_lite_zb",
                            "sonoff_snzb_02p",
                            "sonoff_snzb_04p",
                            "sonoff_snzb_01p");
        }

        @Test
        @DisplayName("the Hue profile round-trips the measured 5-block confirmation[]")
        void hueConfirmationRoundTrip() {
            DeviceProfile hue = loader.loadBundled().stream()
                    .filter(e -> e.profileId()
                            .equals(MeasuredCorpusValues.HUE_PROFILE_ID))
                    .findFirst().orElseThrow()
                    .materialize();

            assertThat(hue.confirmation())
                    .containsExactlyElementsOf(
                            MeasuredCorpusValues.HUE_CONFIRMATION_BLOCK);
            assertThat(hue.matches())
                    .hasAtLeastOneElementOfType(ExactModel.class)
                    .hasAtLeastOneElementOfType(Fingerprint.class);
        }

        @Test
        @DisplayName("the SNZB-03P profile round-trips the measured EMPTY block")
        void snzbEmptyBlockRoundTrip() {
            DeviceProfile snzb = loader.loadBundled().stream()
                    .filter(e -> e.profileId()
                            .equals(MeasuredCorpusValues.SNZB_PROFILE_ID))
                    .findFirst().orElseThrow()
                    .materialize();

            assertThat(snzb.confirmation()).isEmpty();
            assertThat(snzb.matches())
                    .hasAtLeastOneElementOfType(ExactModel.class)
                    .hasAtLeastOneElementOfType(Fingerprint.class);
        }

        @Test
        @DisplayName("reporting overrides and interview skips round-trip when present")
        void optionalSectionsRoundTrip() {
            String json = wrap("""
                    {
                      "profileId": "aqara_weather_sensor",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "LUMI", "model": "lumi.weather"}],
                      "category": "MIXED_CUSTOM",
                      "manufacturerCodec": "xiaomi_ff01",
                      "interviewSkips": ["configure_reporting"],
                      "reportingOverrides": {
                        "0x0402": {"minInterval": 5, "maxInterval": 1800,
                                   "reportableChange": 5}
                      }
                    }
                    """);

            DeviceProfile profile =
                    loader.parse(json, ProfileSource.USER).get(0).materialize();

            assertThat(profile.manufacturerCodec()).isEqualTo("xiaomi_ff01");
            assertThat(profile.interviewSkips())
                    .containsExactly("configure_reporting");
            assertThat(profile.reportingOverrides())
                    .containsEntry(0x0402,
                            new ReportingOverride(0x0402, 5, 1800, 5));
        }
    }
}
