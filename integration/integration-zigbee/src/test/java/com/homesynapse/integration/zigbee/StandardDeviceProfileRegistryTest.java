/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StandardDeviceProfileRegistry} resolution tests: the Doc 18 §3.5(d)
 * precedence ({@code Fingerprint > ExactModel > ModelWildcard}), user-over-bundled
 * at equal rank (§I), the explicit priority tiebreak, the deterministic
 * profileId total order, and the F-12 match-site catch (a body-load failure at
 * match time is that entry's no-match — one WARN naming the profile, siblings
 * still match).
 */
class StandardDeviceProfileRegistryTest {

    private final ZigbeeProfileLoader loader = new ZigbeeProfileLoader();
    private final StandardDeviceProfileRegistry registry =
            new StandardDeviceProfileRegistry();

    private static String wrap(String profilesJson) {
        return """
                {
                  "schemaVersion": {"major": 1, "minor": 0},
                  "profiles": [%s]
                }
                """.formatted(profilesJson);
    }

    private void load(String profilesJson, ProfileSource source) {
        registry.register(loader.parse(wrap(profilesJson), source));
    }

    private static InterviewResult snzbInterview() {
        return new InterviewResult(
                new IEEEAddress(0x00124B0012345678L),
                0x6B9A,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500, 0xFC57),
                        List.of(0x0003, 0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    @Nested
    @DisplayName("string-path resolution (ExactModel / ModelWildcard only)")
    class StringPath {

        @Test
        @DisplayName("exact model beats manufacturer-family wildcard")
        void exactBeatsWildcard() {
            load("""
                    {
                      "profileId": "tradfri_family",
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "IKEA of Sweden",
                                   "modelPrefix": "TRADFRI"}],
                      "category": "MINOR_QUIRKS"
                    },
                    {
                      "profileId": "tradfri_bulb_980",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "IKEA of Sweden",
                                   "model": "TRADFRI bulb E27 WS opal 980lm"}],
                      "category": "MINOR_QUIRKS"
                    }
                    """, ProfileSource.BUNDLED);

            Optional<DeviceProfile> match = registry.findProfile(
                    "IKEA of Sweden", "TRADFRI bulb E27 WS opal 980lm");

            assertThat(match).isPresent();
            assertThat(match.get().profileId()).isEqualTo("tradfri_bulb_980");
        }

        @Test
        @DisplayName("no criteria match yields empty")
        void noMatchYieldsEmpty() {
            load("""
                    {
                      "profileId": "tradfri_family",
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "IKEA of Sweden",
                                   "modelPrefix": "TRADFRI"}],
                      "category": "MINOR_QUIRKS"
                    }
                    """, ProfileSource.BUNDLED);

            assertThat(registry.findProfile("eWeLink", "SNZB-03P")).isEmpty();
        }

        @Test
        @DisplayName("a fingerprint-only profile never matches on the string path")
        void fingerprintNeverMatchesStringPath() {
            load("""
                    {
                      "profileId": "fingerprint_only",
                      "matches": [{"type": "fingerprint",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P",
                                   "endpoints": [{"profileId": 260, "deviceType": 263,
                                                  "inClusters": [0, 1030],
                                                  "outClusters": [25]}]}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            assertThat(registry.findProfile("eWeLink", "SNZB-03P")).isEmpty();
        }
    }

    @Nested
    @DisplayName("interview-path resolution (the fingerprint-capable widening)")
    class InterviewPath {

        @Test
        @DisplayName("a Fingerprint-typed profile present but unmatched: ExactModel wins over ModelWildcard")
        void precedenceWithFingerprintPresent() {
            load("""
                    {
                      "profileId": "sonoff_family",
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "eWeLink", "modelPrefix": "SNZB"}],
                      "category": "STANDARD_ZCL"
                    },
                    {
                      "profileId": "sonoff_snzb_03p_exact",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "STANDARD_ZCL"
                    },
                    {
                      "profileId": "sonoff_snzb_03p_fingerprint",
                      "matches": [{"type": "fingerprint",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P",
                                   "endpoints": [{"profileId": 260, "deviceType": 263,
                                                  "inClusters": [0, 1030],
                                                  "outClusters": [25]}]}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            Optional<DeviceProfile> match = registry.findProfile(snzbInterview());

            // Fingerprint matching is Wave-2: the fingerprint profile is present
            // but contributes no match — it never silently matches.
            assertThat(match).isPresent();
            assertThat(match.get().profileId()).isEqualTo("sonoff_snzb_03p_exact");
        }

        @Test
        @DisplayName("interview path rejects null")
        void rejectsNull() {
            assertThatThrownBy(() -> registry.findProfile((InterviewResult) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("tiebreaks (deterministic total order)")
    class Tiebreaks {

        @Test
        @DisplayName("within a tier, higher explicit priority wins")
        void priorityTiebreak() {
            load("""
                    {
                      "profileId": "family_low",
                      "priority": 1,
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "eWeLink", "modelPrefix": "SNZB"}],
                      "category": "STANDARD_ZCL"
                    },
                    {
                      "profileId": "family_high",
                      "priority": 9,
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "eWeLink", "modelPrefix": "SNZB"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            assertThat(registry.findProfile("eWeLink", "SNZB-03P").orElseThrow()
                    .profileId()).isEqualTo("family_high");
        }

        @Test
        @DisplayName("equal tier and priority: profileId lexicographic ascending is the final key")
        void profileIdTotalOrder() {
            load("""
                    {
                      "profileId": "zeta_family",
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "eWeLink", "modelPrefix": "SNZB"}],
                      "category": "STANDARD_ZCL"
                    },
                    {
                      "profileId": "alpha_family",
                      "matches": [{"type": "model_wildcard",
                                   "manufacturer": "eWeLink", "modelPrefix": "SNZB"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            assertThat(registry.findProfile("eWeLink", "SNZB-03P").orElseThrow()
                    .profileId()).isEqualTo("alpha_family");
        }

        @Test
        @DisplayName("user profiles take precedence over bundled at equal rank (§I)")
        void userOverBundled() {
            load("""
                    {
                      "profileId": "bundled_exact",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);
            load("""
                    {
                      "profileId": "user_exact",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "MINOR_QUIRKS"
                    }
                    """, ProfileSource.USER);

            assertThat(registry.findProfile("eWeLink", "SNZB-03P").orElseThrow()
                    .profileId()).isEqualTo("user_exact");
        }

        @Test
        @DisplayName("a same-id later registration replaces the earlier one (the override channel)")
        void sameIdReplaces() {
            load("""
                    {
                      "profileId": "sonoff_snzb_03p",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);
            load("""
                    {
                      "profileId": "sonoff_snzb_03p",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "MINOR_QUIRKS"
                    }
                    """, ProfileSource.USER);

            assertThat(registry.allProfiles()).hasSize(1);
            assertThat(registry.findProfile("eWeLink", "SNZB-03P").orElseThrow()
                    .category()).isEqualTo(DeviceCategory.MINOR_QUIRKS);
        }
    }

    @Nested
    @DisplayName("match-time body failure (F-12 — no-match, one WARN, siblings still match)")
    class MatchTimeBodyFailure {

        private static final String CORRUPT_BODY_PROFILE = """
                {
                  "profileId": "%s",
                  "matches": [{"type": "exact_model",
                               "manufacturer": "Acme", "model": "X1"}],
                  "category": "NOT_A_REAL_CATEGORY"
                }
                """;

        private ListAppender<ILoggingEvent> logCapture;

        @BeforeEach
        void attachLogCapture() {
            logCapture = new ListAppender<>();
            logCapture.start();
            registryLogger().addAppender(logCapture);
        }

        @AfterEach
        void detachLogCapture() {
            registryLogger().detachAppender(logCapture);
        }

        private Logger registryLogger() {
            return (Logger) LoggerFactory
                    .getLogger(StandardDeviceProfileRegistry.class);
        }

        private List<ILoggingEvent> warns() {
            return logCapture.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
        }

        @Test
        @DisplayName("a corrupt-body winner is a no-match with ONE WARN; the healthy sibling still matches")
        void corruptWinnerFallsToHealthySibling() {
            // aaa_corrupt wins the total order (equal tier/source/priority,
            // profileId ascending) — its body failure must fall to the sibling,
            // never take down the whole match.
            load(CORRUPT_BODY_PROFILE.formatted("aaa_corrupt") + ","
                    + """
                    {
                      "profileId": "zzz_healthy",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "Acme", "model": "X1"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            Optional<DeviceProfile> match = registry.findProfile("Acme", "X1");

            assertThat(match).isPresent();
            assertThat(match.get().profileId()).isEqualTo("zzz_healthy");
            assertThat(warns()).hasSize(1);
            assertThat(warns().get(0).getFormattedMessage())
                    .contains("aaa_corrupt");
        }

        @Test
        @DisplayName("a corrupt body with no sibling yields empty with ONE WARN naming the profileId")
        void corruptOnlyYieldsEmpty() {
            load(CORRUPT_BODY_PROFILE.formatted("only_corrupt"),
                    ProfileSource.BUNDLED);

            assertThat(registry.findProfile("Acme", "X1")).isEmpty();
            assertThat(warns()).hasSize(1);
            assertThat(warns().get(0).getFormattedMessage())
                    .contains("only_corrupt");
        }

        @Test
        @DisplayName("an unknown degradeRule (F-15) surfacing at match time degrades to no-match (F-12)")
        void unknownDegradeRuleAtMatchTime() {
            load("""
                    {
                      "profileId": "aaa_bad_vocabulary",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "Acme", "model": "X1"}],
                      "category": "STANDARD_ZCL",
                      "confirmation": [
                        {
                          "capability": "on_off",
                          "confirmationMode": "EXACT_MATCH",
                          "reportsAuthoritative": "VERIFIED_REPORTS",
                          "reportingPosture": "ON_CHANGE",
                          "confirmability": "CONFIRMABLE",
                          "recommendedTimeoutMs": 5000,
                          "degradeRule": ["RETRY_UNTIL_HEARD"]
                        }
                      ]
                    },
                    {
                      "profileId": "zzz_healthy",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "Acme", "model": "X1"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            Optional<DeviceProfile> match = registry.findProfile("Acme", "X1");

            assertThat(match).isPresent();
            assertThat(match.get().profileId()).isEqualTo("zzz_healthy");
            assertThat(warns()).hasSize(1);
            assertThat(warns().get(0).getFormattedMessage())
                    .contains("aaa_bad_vocabulary")
                    .contains("degradeRule");
        }

        @Test
        @DisplayName("the interview path shares the catch: a corrupt winner falls to the sibling")
        void interviewPathSharesCatch() {
            load("""
                    {
                      "profileId": "aaa_corrupt",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "NOT_A_REAL_CATEGORY"
                    },
                    {
                      "profileId": "zzz_healthy",
                      "matches": [{"type": "exact_model",
                                   "manufacturer": "eWeLink", "model": "SNZB-03P"}],
                      "category": "STANDARD_ZCL"
                    }
                    """, ProfileSource.BUNDLED);

            Optional<DeviceProfile> match = registry.findProfile(snzbInterview());

            assertThat(match).isPresent();
            assertThat(match.get().profileId()).isEqualTo("zzz_healthy");
            assertThat(warns()).hasSize(1);
            assertThat(warns().get(0).getFormattedMessage())
                    .contains("aaa_corrupt");
        }
    }

    @Nested
    @DisplayName("frozen surface behavior")
    class FrozenSurface {

        @Test
        @DisplayName("registerProfile registers at runtime rank and replaces by id")
        void registerProfileReplacesById() {
            DeviceProfile profile = new DeviceProfile("runtime_profile",
                    java.util.Set.of(new ExactModel("Acme", "X1")),
                    DeviceCategory.STANDARD_ZCL,
                    null, null, null, null, null, null, null);
            DeviceProfile replacement = new DeviceProfile("runtime_profile",
                    java.util.Set.of(new ExactModel("Acme", "X1")),
                    DeviceCategory.MINOR_QUIRKS,
                    null, null, null, null, null, null, null);

            registry.registerProfile(profile);
            registry.registerProfile(replacement);

            assertThat(registry.allProfiles()).hasSize(1);
            assertThat(registry.findProfile("Acme", "X1").orElseThrow()
                    .category()).isEqualTo(DeviceCategory.MINOR_QUIRKS);
        }

        @Test
        @DisplayName("allProfiles returns an unmodifiable collection")
        void allProfilesUnmodifiable() {
            registry.registerProfile(new DeviceProfile("p1",
                    java.util.Set.of(new ExactModel("Acme", "X1")),
                    DeviceCategory.STANDARD_ZCL,
                    null, null, null, null, null, null, null));

            assertThatThrownBy(() -> registry.allProfiles().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
