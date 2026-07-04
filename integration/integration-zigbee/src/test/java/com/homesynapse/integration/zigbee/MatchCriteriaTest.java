/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §1.2 sealed {@link MatchCriteria} tests (DP-2: all three variants DEFINED now,
 * matching implemented for the first two only; the hierarchy seals forever —
 * Wave-2 populates, never re-seals).
 */
class MatchCriteriaTest {

    @Nested
    @DisplayName("ExactModel")
    class ExactModelTests {

        @Test
        @DisplayName("matches on exact manufacturer + model strings")
        void exactStringMatch() {
            ExactModel criteria = new ExactModel(
                    MeasuredCorpusValues.HUE_MANUFACTURER,
                    MeasuredCorpusValues.HUE_MODEL);

            assertThat(criteria.matches(MeasuredCorpusValues.HUE_MANUFACTURER,
                    MeasuredCorpusValues.HUE_MODEL)).isTrue();
            assertThat(criteria.matches("Signify", MeasuredCorpusValues.HUE_MODEL))
                    .isFalse();
            assertThat(criteria.matches(MeasuredCorpusValues.HUE_MANUFACTURER,
                    "LCA006")).isFalse();
        }

        @Test
        @DisplayName("rejects null components")
        void rejectsNull() {
            assertThatThrownBy(() -> new ExactModel(null, "LCA017"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExactModel("Signify Netherlands B.V.", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ModelWildcard")
    class ModelWildcardTests {

        @Test
        @DisplayName("matches on exact manufacturer + model prefix (the TRADFRI* family case)")
        void prefixMatch() {
            ModelWildcard criteria = new ModelWildcard("IKEA of Sweden", "TRADFRI");

            assertThat(criteria.matches("IKEA of Sweden",
                    "TRADFRI bulb E27 WS opal 980lm")).isTrue();
            assertThat(criteria.matches("IKEA of Sweden", "SYMFONISK remote"))
                    .isFalse();
            assertThat(criteria.matches("eWeLink", "TRADFRI bulb")).isFalse();
        }

        @Test
        @DisplayName("rejects null components")
        void rejectsNull() {
            assertThatThrownBy(() -> new ModelWildcard(null, "TRADFRI"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ModelWildcard("IKEA of Sweden", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Fingerprint (DEFINED now; matching behavior is Wave-2)")
    class FingerprintTests {

        @Test
        @DisplayName("carries the corpus IR fingerprint shape (mfr, model, endpoint signatures)")
        void carriesIrShape() {
            Fingerprint fingerprint = new Fingerprint(
                    MeasuredCorpusValues.SNZB_MANUFACTURER,
                    MeasuredCorpusValues.SNZB_MODEL,
                    List.of(MeasuredCorpusValues.SNZB_EP1_SIGNATURE));

            assertThat(fingerprint.manufacturerName())
                    .isEqualTo(MeasuredCorpusValues.SNZB_MANUFACTURER);
            assertThat(fingerprint.modelIdentifier())
                    .isEqualTo(MeasuredCorpusValues.SNZB_MODEL);
            assertThat(fingerprint.endpoints())
                    .containsExactly(MeasuredCorpusValues.SNZB_EP1_SIGNATURE);
        }

        @Test
        @DisplayName("match() throws UnsupportedOperationException with the Wave-2 pointer")
        void matchThrowsUnsupported() {
            Fingerprint fingerprint = new Fingerprint(
                    MeasuredCorpusValues.SNZB_MANUFACTURER,
                    MeasuredCorpusValues.SNZB_MODEL,
                    List.of(MeasuredCorpusValues.SNZB_EP1_SIGNATURE));

            assertThatThrownBy(() -> fingerprint.matches("eWeLink", "SNZB-03P"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Wave-2");
        }

        @Test
        @DisplayName("endpoint list is defensively copied and non-empty")
        void endpointValidation() {
            assertThatThrownBy(() -> new Fingerprint("eWeLink", "SNZB-03P", null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Fingerprint("eWeLink", "SNZB-03P", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("EndpointSignature (pinned from the corpus IR identity.fingerprint[] fields)")
    class EndpointSignatureTests {

        @Test
        @DisplayName("carries profileId / deviceType / in+out clusters")
        void carriesIrFields() {
            EndpointSignature signature = MeasuredCorpusValues.HUE_EP11_SIGNATURE;

            assertThat(signature.profileId()).isEqualTo(0x0104);
            assertThat(signature.deviceType()).isEqualTo(0x010D);
            assertThat(signature.inClusters()).contains(0x0006, 0x0008, 0x0300);
            assertThat(signature.outClusters()).containsExactly(0x0019);
        }

        @Test
        @DisplayName("validates non-negative ids and non-null cluster sets")
        void validation() {
            assertThatThrownBy(() -> new EndpointSignature(-1, 0x010D,
                    Set.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EndpointSignature(0x0104, -1,
                    Set.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EndpointSignature(0x0104, 0x010D,
                    null, Set.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new EndpointSignature(0x0104, 0x010D,
                    Set.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("the hierarchy is sealed with exactly the three ratified permits")
    void sealedWithThreePermits() {
        assertThat(MatchCriteria.class.isSealed()).isTrue();
        assertThat(MatchCriteria.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        ExactModel.class, ModelWildcard.class, Fingerprint.class);
    }

    @Test
    @DisplayName("exhaustive switch over the permits compiles without a default arm")
    void exhaustiveSwitch() {
        MatchCriteria criteria = new ExactModel("eWeLink", "SNZB-03P");
        String kind = switch (criteria) {
            case ExactModel ignored -> "exact";
            case ModelWildcard ignored -> "wildcard";
            case Fingerprint ignored -> "fingerprint";
        };

        assertThat(kind).isEqualTo("exact");
    }
}
