/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.ConfirmationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AMD-97 §1.1 characterization tests: the {@link ConfirmationCharacterization}
 * record realizes the ratified eight-field {@code confirmation[]} block, and the
 * MEASURED Wave-1 values (bench 2026-07-01) populate it exactly as recorded.
 *
 * <p>The never-false-CONFIRMED shape (AMD-97-INV-01) is asserted here at the
 * characterization level; the full command-path acceptance suite is M9.4's.
 */
class ConfirmationCharacterizationTest {

    @Nested
    @DisplayName("record contract")
    class RecordContract {

        @Test
        @DisplayName("carries the eight AMD-97 fields plus the notes sibling")
        void carriesAllFields() {
            ConfirmationCharacterization c = MeasuredCorpusValues.HUE_ON_OFF;

            assertThat(c.capability()).isEqualTo("on_off");
            assertThat(c.confirmationMode()).isEqualTo(ConfirmationMode.EXACT_MATCH);
            assertThat(c.authoritativeAttribute()).isEqualTo("OnOff/0x0000");
            assertThat(c.reportsAuthoritative())
                    .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
            assertThat(c.reportingPosture()).isEqualTo(ReportingPosture.ON_CHANGE);
            assertThat(c.confirmability()).isEqualTo(Confirmability.CONFIRMABLE);
            assertThat(c.recommendedTimeoutMs()).isEqualTo(5000L);
            assertThat(c.degradeRule())
                    .isEqualTo(MeasuredCorpusValues.HUE_CONFIRMABLE_DEGRADE);
            assertThat(c.notes()).isNotBlank();
        }

        @Test
        @DisplayName("authoritativeAttribute is null when none exists (the identify class)")
        void authoritativeAttributeNullable() {
            assertThat(MeasuredCorpusValues.HUE_IDENTIFY.authoritativeAttribute())
                    .isNull();
        }

        @Test
        @DisplayName("notes is the nullable free-text sibling — never the rule")
        void notesNullable() {
            ConfirmationCharacterization c = new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, "OnOff/0x0000",
                    ReportsAuthoritative.VERIFIED_REPORTS, ReportingPosture.ON_CHANGE,
                    Confirmability.CONFIRMABLE, 5000L,
                    Set.of(DegradeRule.NO_REPORT_TIMEOUT_TO_UNCONFIRMED), null);

            assertThat(c.notes()).isNull();
        }

        @Test
        @DisplayName("required fields reject null")
        void requiredFieldsRejectNull() {
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    null, ConfirmationMode.EXACT_MATCH, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, 0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", null, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, 0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, null,
                    null, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, 0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, null,
                    ReportsAuthoritative.NONE, null,
                    Confirmability.UNCONFIRMABLE, 0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    null, 0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, 0L, null, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("recommendedTimeoutMs must be non-negative")
        void timeoutNonNegative() {
            assertThatThrownBy(() -> new ConfirmationCharacterization(
                    "on_off", ConfirmationMode.EXACT_MATCH, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, -1L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("degradeRule is a defensively copied composable set")
        void degradeRuleDefensivelyCopied() {
            java.util.Set<DegradeRule> mutable = new java.util.HashSet<>();
            mutable.add(DegradeRule.IMMEDIATE_UNCONFIRMED);
            ConfirmationCharacterization c = new ConfirmationCharacterization(
                    "identify", ConfirmationMode.DISABLED, null,
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE, 0L, mutable, null);
            mutable.add(DegradeRule.NACK_TO_FAILED);

            assertThat(c.degradeRule())
                    .containsExactly(DegradeRule.IMMEDIATE_UNCONFIRMED);
            assertThatThrownBy(() -> c.degradeRule().add(DegradeRule.NACK_TO_FAILED))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("the AMD-97 enums (verbatim value sets)")
    class EnumShapes {

        @Test
        @DisplayName("ReportsAuthoritative: VERIFIED_REPORTS | READBACK_ONLY | NONE")
        void reportsAuthoritative() {
            assertThat(ReportsAuthoritative.values()).containsExactly(
                    ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportsAuthoritative.READBACK_ONLY,
                    ReportsAuthoritative.NONE);
        }

        @Test
        @DisplayName("ReportingPosture: ON_CHANGE | PERIODIC | SLEEPY | NONE")
        void reportingPosture() {
            assertThat(ReportingPosture.values()).containsExactly(
                    ReportingPosture.ON_CHANGE,
                    ReportingPosture.PERIODIC,
                    ReportingPosture.SLEEPY,
                    ReportingPosture.NONE);
        }

        @Test
        @DisplayName("Confirmability: CONFIRMABLE | BEST_EFFORT | UNCONFIRMABLE")
        void confirmability() {
            assertThat(Confirmability.values()).containsExactly(
                    Confirmability.CONFIRMABLE,
                    Confirmability.BEST_EFFORT,
                    Confirmability.UNCONFIRMABLE);
        }

        @Test
        @DisplayName("DegradeRule: the four ratified composable values")
        void degradeRule() {
            assertThat(DegradeRule.values()).containsExactly(
                    DegradeRule.NO_REPORT_TIMEOUT_TO_UNCONFIRMED,
                    DegradeRule.NACK_TO_FAILED,
                    DegradeRule.IMMEDIATE_UNCONFIRMED,
                    DegradeRule.CONFIRM_FROM_CACHE_OR_READBACK);
        }
    }

    @Nested
    @DisplayName("measured Wave-1 blocks (bench 2026-07-01 — the ratified reference values)")
    class MeasuredBlocks {

        @Test
        @DisplayName("Hue on_off/brightness: CONFIRMABLE, VERIFIED_REPORTS, ON_CHANGE, 5000 ms")
        void hueOnOffBrightness() {
            for (ConfirmationCharacterization c : List.of(
                    MeasuredCorpusValues.HUE_ON_OFF,
                    MeasuredCorpusValues.HUE_BRIGHTNESS)) {
                assertThat(c.confirmability()).isEqualTo(Confirmability.CONFIRMABLE);
                assertThat(c.reportsAuthoritative())
                        .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
                assertThat(c.reportingPosture()).isEqualTo(ReportingPosture.ON_CHANGE);
                assertThat(c.recommendedTimeoutMs()).isEqualTo(5000L);
            }
        }

        @Test
        @DisplayName("Hue color_temperature: CONFIRMABLE, TOLERANCE mode, 15000 ms")
        void hueColorTemperature() {
            ConfirmationCharacterization c =
                    MeasuredCorpusValues.HUE_COLOR_TEMPERATURE;

            assertThat(c.confirmability()).isEqualTo(Confirmability.CONFIRMABLE);
            assertThat(c.confirmationMode()).isEqualTo(ConfirmationMode.TOLERANCE);
            assertThat(c.recommendedTimeoutMs()).isEqualTo(15000L);
        }

        @Test
        @DisplayName("Hue effect: UNCONFIRMABLE, READBACK_ONLY, immediate-UNCONFIRMED")
        void hueEffect() {
            ConfirmationCharacterization c = MeasuredCorpusValues.HUE_EFFECT;

            assertThat(c.confirmability()).isEqualTo(Confirmability.UNCONFIRMABLE);
            assertThat(c.reportsAuthoritative())
                    .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
            assertThat(c.degradeRule())
                    .containsExactly(DegradeRule.IMMEDIATE_UNCONFIRMED);
        }

        @Test
        @DisplayName("Hue identify: UNCONFIRMABLE strict — reportsAuthoritative NONE")
        void hueIdentify() {
            ConfirmationCharacterization c = MeasuredCorpusValues.HUE_IDENTIFY;

            assertThat(c.confirmability()).isEqualTo(Confirmability.UNCONFIRMABLE);
            assertThat(c.reportsAuthoritative()).isEqualTo(ReportsAuthoritative.NONE);
        }

        @Test
        @DisplayName("the E5-#5 taxonomy split: never-reported vs no-attribute, no fourth verdict")
        void taxonomySplitCarriedByFieldPair() {
            // Both are UNCONFIRMABLE; the pair confirmability × reportsAuthoritative
            // carries the measured split (READBACK_ONLY vs NONE) without a fourth
            // Confirmability value.
            assertThat(MeasuredCorpusValues.HUE_EFFECT.confirmability())
                    .isEqualTo(MeasuredCorpusValues.HUE_IDENTIFY.confirmability());
            assertThat(MeasuredCorpusValues.HUE_EFFECT.reportsAuthoritative())
                    .isNotEqualTo(MeasuredCorpusValues.HUE_IDENTIFY.reportsAuthoritative());
        }

        @Test
        @DisplayName("SNZB-03P: the measured EMPTY block (read-only sensor)")
        void snzbEmptyBlock() {
            assertThat(MeasuredCorpusValues.SNZB_CONFIRMATION_BLOCK).isEmpty();
        }

        @Test
        @DisplayName("AMD-97-INV-01 characterization shape: DISABLED/UNCONFIRMABLE entries "
                + "carry immediate-UNCONFIRMED and a zero report-wait")
        void neverFalseConfirmedShape() {
            for (ConfirmationCharacterization c :
                    MeasuredCorpusValues.HUE_CONFIRMATION_BLOCK) {
                if (c.confirmability() == Confirmability.UNCONFIRMABLE
                        || c.confirmationMode() == ConfirmationMode.DISABLED) {
                    assertThat(c.degradeRule())
                            .as("an unconfirmable capability renders UNCONFIRMED "
                                    + "immediately, never a false CONFIRMED")
                            .contains(DegradeRule.IMMEDIATE_UNCONFIRMED);
                    assertThat(c.recommendedTimeoutMs())
                            .as("no report-wait window exists for %s", c.capability())
                            .isZero();
                }
            }
        }
    }
}
