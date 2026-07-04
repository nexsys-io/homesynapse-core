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
 * error), the index-first shape (§F — bodies parse lazily, on match), and the
 * bundled-corpus round-trip including the AMD-97 {@code confirmation[]} block.
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
    @DisplayName("bundled corpus round-trip (the measured Wave-1 values)")
    class BundledCorpus {

        @Test
        @DisplayName("the bundled resource loads both Wave-1 profiles")
        void bundledResourceLoads() {
            List<ProfileEntry> entries = loader.loadBundled();

            assertThat(entries).extracting(ProfileEntry::profileId)
                    .containsExactlyInAnyOrder(
                            MeasuredCorpusValues.HUE_PROFILE_ID,
                            MeasuredCorpusValues.SNZB_PROFILE_ID);
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
