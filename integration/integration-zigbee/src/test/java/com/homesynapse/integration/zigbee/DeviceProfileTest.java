/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §1.1 {@link DeviceProfile} tests after the AMD-97 realization: ten components
 * ({@code confirmation} appended after {@code initializationWrites}), the sealed
 * {@link MatchCriteria} retype of {@code matches}, and the SIX-nullable-collections
 * conditional-copy contract (the former five-nullable gotcha plus the new block).
 */
class DeviceProfileTest {

    private static DeviceProfile minimal(Set<MatchCriteria> matches) {
        return new DeviceProfile("philips_hue_white_color_a19", matches,
                DeviceCategory.STANDARD_ZCL, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("ten components: confirmation[] is component 10, after initializationWrites")
    void tenComponents() {
        assertThat(DeviceProfile.class.getRecordComponents()).hasSize(10);
        assertThat(DeviceProfile.class.getRecordComponents()[9].getName())
                .isEqualTo("confirmation");
        assertThat(DeviceProfile.class.getRecordComponents()[8].getName())
                .isEqualTo("initializationWrites");
    }

    @Test
    @DisplayName("matches is a Set<MatchCriteria>: all three permits coexist in one profile")
    void matchesRetypedToCriteria() {
        DeviceProfile profile = minimal(Set.of(
                new ExactModel(MeasuredCorpusValues.HUE_MANUFACTURER,
                        MeasuredCorpusValues.HUE_MODEL),
                new ModelWildcard("IKEA of Sweden", "TRADFRI"),
                new Fingerprint(MeasuredCorpusValues.HUE_MANUFACTURER,
                        MeasuredCorpusValues.HUE_MODEL,
                        List.of(MeasuredCorpusValues.HUE_EP11_SIGNATURE))));

        assertThat(profile.matches()).hasSize(3);
    }

    @Test
    @DisplayName("carries the measured Hue confirmation block; null and empty both mean read-only")
    void confirmationBlockCarried() {
        DeviceProfile withBlock = new DeviceProfile(
                MeasuredCorpusValues.HUE_PROFILE_ID,
                Set.of(new ExactModel(MeasuredCorpusValues.HUE_MANUFACTURER,
                        MeasuredCorpusValues.HUE_MODEL)),
                DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null,
                MeasuredCorpusValues.HUE_CONFIRMATION_BLOCK);
        DeviceProfile emptyBlock = new DeviceProfile(
                MeasuredCorpusValues.SNZB_PROFILE_ID,
                Set.of(new ExactModel(MeasuredCorpusValues.SNZB_MANUFACTURER,
                        MeasuredCorpusValues.SNZB_MODEL)),
                DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null,
                MeasuredCorpusValues.SNZB_CONFIRMATION_BLOCK);
        DeviceProfile nullBlock = minimal(Set.of(
                new ExactModel(MeasuredCorpusValues.SNZB_MANUFACTURER,
                        MeasuredCorpusValues.SNZB_MODEL)));

        assertThat(withBlock.confirmation()).hasSize(5);
        assertThat(emptyBlock.confirmation()).isEmpty();
        assertThat(nullBlock.confirmation()).isNull();
    }

    @Test
    @DisplayName("confirmation is the sixth nullable collection: conditional defensive copy")
    void confirmationConditionalCopy() {
        List<ConfirmationCharacterization> mutable = new ArrayList<>();
        mutable.add(MeasuredCorpusValues.HUE_ON_OFF);
        DeviceProfile profile = new DeviceProfile(
                MeasuredCorpusValues.HUE_PROFILE_ID,
                Set.of(new ExactModel(MeasuredCorpusValues.HUE_MANUFACTURER,
                        MeasuredCorpusValues.HUE_MODEL)),
                DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null, mutable);
        mutable.add(MeasuredCorpusValues.HUE_IDENTIFY);

        assertThat(profile.confirmation())
                .containsExactly(MeasuredCorpusValues.HUE_ON_OFF);
        assertThatThrownBy(() -> profile.confirmation()
                .add(MeasuredCorpusValues.HUE_IDENTIFY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("frozen validation unchanged: profileId/matches/category required, matches non-empty")
    void frozenValidationUnchanged() {
        Set<MatchCriteria> matches =
                Set.of(new ExactModel("eWeLink", "SNZB-03P"));

        assertThatThrownBy(() -> new DeviceProfile(null, matches,
                DeviceCategory.STANDARD_ZCL, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeviceProfile("p", null,
                DeviceCategory.STANDARD_ZCL, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeviceProfile("p", Set.of(),
                DeviceCategory.STANDARD_ZCL, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceProfile("p", matches,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
