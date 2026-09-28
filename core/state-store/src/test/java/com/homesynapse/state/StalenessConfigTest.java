/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.EntityId;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IR-61b — {@link StalenessConfig}: Doc 03 §9's {@code state_store.staleness} block parsed into
 * the two values the threshold chain reads. Every duration is ISO-8601 time-based
 * ({@code PT…}), the automation section's grammar; a malformed value or key throws
 * {@link IllegalArgumentException} naming its full key path; {@code scan_interval_seconds} is
 * accepted with no effect. No clock is read.
 */
@DisplayName("StalenessConfig — Doc 03 §9's staleness keys (IR-61b)")
final class StalenessConfigTest {

    private static final String PLUG_ULID = "01JAAAAAAAAAAAAAAAAAAAAAAC";
    private static final EntityId PLUG = EntityId.parse(PLUG_ULID);
    private static final String THRESHOLD_PATH = "state_store.staleness.default_staleness_threshold";

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StalenessConfigTest() {
    }

    @Test
    @DisplayName("T1: an empty section → NONE; an absent block, YAML nulls and other state_store "
            + "keys read the same")
    void emptySection_isNone() {
        StalenessConfig empty = StalenessConfig.fromStateStoreSection(Map.of());
        assertThat(empty).isEqualTo(StalenessConfig.NONE);
        assertThat(empty.defaultThreshold()).isEqualTo(Optional.empty());
        assertThat(empty.overrides()).isEqualTo(Map.of());

        Map<String, Object> nullBlock = new HashMap<>();
        nullBlock.put("staleness", null);
        Map<String, Object> nullKeys = new HashMap<>();
        nullKeys.put("default_staleness_threshold", null);
        nullKeys.put("staleness_overrides", null);
        assertThat(StalenessConfig.fromStateStoreSection(null)).isEqualTo(StalenessConfig.NONE);
        assertThat(StalenessConfig.fromStateStoreSection(nullBlock))
                .isEqualTo(StalenessConfig.NONE);
        assertThat(StalenessConfig.fromStateStoreSection(Map.of("staleness", nullKeys)))
                .isEqualTo(StalenessConfig.NONE);
        assertThat(StalenessConfig.fromStateStoreSection(Map.of("max_entity_count", 5000,
                "checkpoint", Map.of("interval_minutes", 5))))
                .as("Doc 03 §9's other state_store keys are not this record's")
                .isEqualTo(StalenessConfig.NONE);
    }

    @Test
    @DisplayName("T1b: default_staleness_threshold PT2H + one override PT5M → the two values; "
            + "the override map is unmodifiable")
    void thresholdAndOverride_parse() {
        StalenessConfig config = StalenessConfig.fromStateStoreSection(t1bSection());

        assertThat(config.defaultThreshold()).contains(Duration.ofHours(2));
        assertThat(config.overrides()).isEqualTo(Map.of(PLUG, Duration.ofMinutes(5)));
        assertThatThrownBy(() -> config.overrides().put(PLUG, Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("T1c: PT-5M, the doc's \"10m\" and a non-ULID override key → "
            + "IllegalArgumentException naming the key path")
    void malformedValues_nameTheirKeyPath() {
        assertRejected(threshold("PT-5M"), THRESHOLD_PATH, "positive");
        assertRejected(threshold("10m"), THRESHOLD_PATH, "PT");
        assertRejected(overrides(Map.of("not-a-ulid", "PT5M")),
                "state_store.staleness.staleness_overrides[not-a-ulid]");

        // The grammar's other edges, each named at its path.
        assertRejected(threshold("PT0S"), THRESHOLD_PATH, "positive");
        assertRejected(threshold("P1D"), THRESHOLD_PATH, "PT");
        assertRejected(threshold(20), THRESHOLD_PATH, "PT");
        assertRejected(overrides(Map.of(PLUG_ULID, "5m")),
                "state_store.staleness.staleness_overrides[" + PLUG_ULID + "]", "PT");
        assertRejected(Map.of("staleness", "every five minutes"), "state_store.staleness");
        assertRejected(Map.of("staleness", Map.of("staleness_overrides", "PT5M")),
                "state_store.staleness.staleness_overrides");
    }

    @Test
    @DisplayName("T1d: scan_interval_seconds: 30 present → the record equals T1b's (no effect)")
    void scanInterval_isAcceptedWithNoEffect() {
        Map<String, Object> withScan = Map.of("staleness", Map.of(
                "default_staleness_threshold", "PT2H",
                "scan_interval_seconds", 30,
                "staleness_overrides", Map.of(PLUG_ULID, "PT5M")));

        assertThat(StalenessConfig.fromStateStoreSection(withScan))
                .isEqualTo(StalenessConfig.fromStateStoreSection(t1bSection()))
                .isEqualTo(new StalenessConfig(Optional.of(Duration.ofHours(2)),
                        Map.of(PLUG, Duration.ofMinutes(5))));
    }

    private static Map<String, Object> t1bSection() {
        return Map.of("staleness", Map.of(
                "default_staleness_threshold", "PT2H",
                "staleness_overrides", Map.of(PLUG_ULID, "PT5M")));
    }

    private static Map<String, Object> threshold(Object value) {
        return Map.of("staleness", Map.of("default_staleness_threshold", value));
    }

    private static Map<String, Object> overrides(Map<String, Object> overrides) {
        return Map.of("staleness", Map.of("staleness_overrides", overrides));
    }

    private static void assertRejected(Map<String, Object> section, String... messageParts) {
        assertThatThrownBy(() -> StalenessConfig.fromStateStoreSection(section))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(messageParts);
    }
}
