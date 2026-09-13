/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * BUS-ORDER-1 — the two LIVE read-forward fields of {@link EventBusConfig}:
 * {@code liveReadBatch} (events per LIVE page read, {@code >= 1}) and
 * {@code liveIdleTick} (the LIVE loop's bounded park, {@code >= 1 ms}), their
 * {@link EventBusConfig#HOME_DEFAULT} values, and the two-field convenience
 * form that supplies the defaults for the M3.6b call sites.
 */
@DisplayName("EventBusConfig — the LIVE read-forward fields (BUS-ORDER-1)")
final class EventBusConfigLiveFieldsTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    EventBusConfigLiveFieldsTest() {
    }

    @Test
    @DisplayName("HOME_DEFAULT: liveReadBatch=64, liveIdleTick=250 ms; the M3.6b values unchanged")
    void homeDefaultLiveValues() {
        assertThat(EventBusConfig.HOME_DEFAULT.liveReadBatch())
                .as("HOME_DEFAULT.liveReadBatch (AMD-101 §3.5)")
                .isEqualTo(64);
        assertThat(EventBusConfig.HOME_DEFAULT.liveIdleTick())
                .as("HOME_DEFAULT.liveIdleTick (AMD-101 §3.5)")
                .isEqualTo(Duration.ofMillis(250));
        assertThat(EventBusConfig.HOME_DEFAULT.replayQueueCapacity()).isEqualTo(10_000);
        assertThat(EventBusConfig.HOME_DEFAULT.publisherBlockedDepthThreshold()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("the two-field form supplies the LIVE defaults (the M3.6b call sites are byte-unchanged)")
    void twoFieldFormSuppliesLiveDefaults() {
        EventBusConfig config = new EventBusConfig(10_000, 5_000);

        assertThat(config).isEqualTo(EventBusConfig.HOME_DEFAULT);
        assertThat(new EventBusConfig(7, 9))
                .isEqualTo(new EventBusConfig(7, 9, 64, Duration.ofMillis(250)));
    }

    @Test
    @DisplayName("liveReadBatch must be >= 1")
    void rejectsLiveReadBatchBelowOne() {
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, 0, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveReadBatch");
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, -3, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveReadBatch");
        assertThat(new EventBusConfig(10_000, 5_000, 1, Duration.ofMillis(250)).liveReadBatch())
                .as("1 is the floor and is accepted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("liveIdleTick must be >= 1 ms and non-null")
    void rejectsLiveIdleTickBelowOneMillisecond() {
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, 64, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveIdleTick");
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, 64, Duration.ofNanos(999_999L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveIdleTick");
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, 64, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveIdleTick");
        assertThatThrownBy(() -> new EventBusConfig(10_000, 5_000, 64, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("liveIdleTick");
        assertThat(new EventBusConfig(10_000, 5_000, 64, Duration.ofMillis(1)).liveIdleTick())
                .as("1 ms is the floor and is accepted")
                .isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("the M3.6b validations still hold on the four-field form")
    void originalValidationsHold() {
        assertThatThrownBy(() -> new EventBusConfig(0, 5_000, 64, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayQueueCapacity");
        assertThatThrownBy(() -> new EventBusConfig(10_000, 0, 64, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherBlockedDepthThreshold");
    }
}
