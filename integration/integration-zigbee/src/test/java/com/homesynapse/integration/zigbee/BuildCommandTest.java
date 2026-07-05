/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.1 — {@code buildCommand} on the actuator trio (OnOff / LevelControl /
 * ColorControl), byte-exact against the ZCL8 command layouts. The Kelvin→mireds
 * inverse runs at THIS wire boundary only (AMD-96 in reverse); round-trip
 * consistency with ingestion's {@code K = round(1e6 / m)} is asserted directly.
 */
@DisplayName("buildCommand ×3 — the minimal command write path (M9.4a §3.1)")
class BuildCommandTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x0017880109AB12CDL);

    private TestClock clock;
    private OnOffHandler onOff;
    private LevelControlHandler level;
    private ColorControlHandler color;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        onOff = new OnOffHandler(DEVICE, clock);
        level = new LevelControlHandler(DEVICE, clock);
        color = new ColorControlHandler(DEVICE, clock);
    }

    @Test
    @DisplayName("turn_on → OnOff 0x0006 command 0x01, no payload")
    void turnOn_byteExact() {
        ZclFrame frame = onOff.buildCommand("turn_on", Map.of());

        assertThat(frame.clusterId()).isEqualTo(0x0006);
        assertThat(frame.commandId()).isEqualTo(0x01);   // ZCL8 §3.8.2.3: On
        assertThat(frame.isClusterSpecific()).isTrue();
        assertThat(frame.payload()).isEmpty();
    }

    @Test
    @DisplayName("turn_off → OnOff 0x0006 command 0x00, no payload")
    void turnOff_byteExact() {
        ZclFrame frame = onOff.buildCommand("turn_off", Map.of());

        assertThat(frame.commandId()).isEqualTo(0x00);   // ZCL8 §3.8.2.3: Off
        assertThat(frame.payload()).isEmpty();
    }

    @Test
    @DisplayName("set_brightness(50 %) → Move to Level (with On/Off) 0x04, level 127, immediate")
    void setBrightness_byteExact() {
        ZclFrame frame = level.buildCommand("set_brightness", Map.of("level", 50));

        assertThat(frame.clusterId()).isEqualTo(0x0008);
        assertThat(frame.commandId()).isEqualTo(0x04);
        // round(50 × 254 / 100) = round(127.0) = 127 = 0x7F; transition 0x0000.
        assertThat(frame.payload()).containsExactly(0x7F, 0x00, 0x00);
    }

    @Test
    @DisplayName("set_brightness clamps at both edges: 100 % → 254, 0 % → 0, out-of-band input clamps")
    void setBrightness_clamps() {
        assertThat(level.buildCommand("set_brightness", Map.of("level", 100))
                .payload()[0]).isEqualTo((byte) 254);
        assertThat(level.buildCommand("set_brightness", Map.of("level", 0))
                .payload()[0]).isEqualTo((byte) 0);
        // 120 % → round(304.8) = 305 → clamped 254; −5 % → −12.7 → clamped 0.
        assertThat(level.buildCommand("set_brightness", Map.of("level", 120))
                .payload()[0]).isEqualTo((byte) 254);
        assertThat(level.buildCommand("set_brightness", Map.of("level", -5))
                .payload()[0]).isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("SD-2 alignment sweep: the wire level matches the ledger bridge's linear map "
            + "within 1 for every percent 0-100")
    void setBrightness_wireLevelAlignsWithTheDerivationBridge() {
        // M9.4b §2.2 alignment pin (the zigbee half): the ledger's schema-driven
        // rescale derives target = round(percent × 254 / 100) in the 0-254
        // attribute domain; the wire level below must land within 1 of it — the
        // ±2 confirmation tolerance absorbs the residual with margin. The
        // automation half is DeriveOutcomeRescaleTest's sweep against the same
        // literal map.
        for (int percent = 0; percent <= 100; percent++) {
            int wireLevel = level.buildCommand("set_brightness", Map.of("level", percent))
                    .payload()[0] & 0xFF;
            long bridgeTarget = Math.round(percent * 254 / 100.0);
            assertThat(Math.abs(wireLevel - bridgeTarget))
                    .as("percent %s: wire %s vs bridge %s", percent, wireLevel, bridgeTarget)
                    .isLessThanOrEqualTo(1L);
        }
    }

    @Test
    @DisplayName("transition_ms rounds to deciseconds (1500 ms → 15)")
    void transitionParameter_deciseconds() {
        ZclFrame frame = level.buildCommand("set_brightness",
                Map.of("level", 50, "transition_ms", 1500));

        // ZCL8 §3.10.2.3.1: transition u16 LE in 0.1 s units — 1500 ms → 15 = 0x000F.
        assertThat(frame.payload()).containsExactly(0x7F, 0x0F, 0x00);
    }

    @Test
    @DisplayName("set_color_temperature(4525 K) → Move to Color Temperature 0x0A, 221 mireds LE")
    void setColorTemperature_4525K_byteExact() {
        ZclFrame frame = color.buildCommand("set_color_temperature",
                Map.of("kelvin", 4525));

        assertThat(frame.clusterId()).isEqualTo(0x0300);
        assertThat(frame.commandId()).isEqualTo(0x0A);
        // round(1e6 / 4525) = round(220.99) = 221 = 0x00DD LE; transition 0x0000.
        assertThat(frame.payload()).containsExactly(0xDD, 0x00, 0x00, 0x00);
    }

    @Test
    @DisplayName("set_color_temperature(2702 K) → 370 mireds — round-trip consistent with ingestion's 1e6/m")
    void setColorTemperature_2702K_roundTrip() {
        ZclFrame frame = color.buildCommand("set_color_temperature",
                Map.of("kelvin", 2702));

        // round(1e6 / 2702) = round(370.096) = 370 = 0x0172 LE.
        assertThat(frame.payload()).containsExactly(0x72, 0x01, 0x00, 0x00);
        // Round-trip: ingestion converts 370 mireds → round(1e6/370) = 2703 K —
        // the ±1-mired drift class the capability's ±50 K tolerance absorbs.
        assertThat(color.normalize(11, 0x0300, Map.of(0x0007, 370L)).get(0).value())
                .isEqualTo(2703L);
        // And the fixture pair: 221 mireds ingests as exactly 4525 K.
        assertThat(color.normalize(11, 0x0300, Map.of(0x0007, 221L)).get(0).value())
                .isEqualTo(4525L);
    }

    @Test
    @DisplayName("mireds clamp to the ZCL-legal band [1, 0xFEFF] at both edges")
    void setColorTemperature_clamps() {
        // 2,000,000 K → round(0.5) = 1 (already the floor); 2 K → 500,000 → 0xFEFF.
        assertThat(color.buildCommand("set_color_temperature",
                Map.of("kelvin", 2_000_000)).payload()[0]).isEqualTo((byte) 1);
        byte[] ceiling = color.buildCommand("set_color_temperature",
                Map.of("kelvin", 2)).payload();
        assertThat(ceiling[0]).isEqualTo((byte) 0xFF);
        assertThat(ceiling[1]).isEqualTo((byte) 0xFE);
    }

    @Test
    @DisplayName("an unknown command on an implemented handler throws UOE naming handler + command (→ PERMANENT per §1.5)")
    void unknownCommand_throwsUnsupported() {
        assertThatThrownBy(() -> onOff.buildCommand("toggle", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("OnOffHandler")
                .hasMessageContaining("toggle");
        assertThatThrownBy(() -> color.buildCommand("color_loop", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ColorControlHandler")
                .hasMessageContaining("color_loop");
    }
}
