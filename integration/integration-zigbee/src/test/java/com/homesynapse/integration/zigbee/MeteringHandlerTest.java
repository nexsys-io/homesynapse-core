/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MeteringHandler} (0x0702, ENERGY-READ R4 / T5):
 * {@code CurrentSummationDelivered} (uint48) → {@code energy_wh} =
 * {@code raw × multiplier / divisor × 1000} when — and only when — the device
 * declared kWh. Any other unit (or an unread one) is skipped with a debug
 * naming the unit: no guess. The three divisors are the field's recorded
 * candidates (1,000 · 1,000,000 · the TR3's 3,600,000 per z2m); none is a
 * prediction of the owned Gen4's value, which is UNREAD.
 */
@DisplayName("MeteringHandler — 0x0702 CurrentSummationDelivered → energy_wh, "
        + "scaled by the READ formatting (ENERGY-READ R4)")
class MeteringHandlerTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B00AA0000E2L);

    private TestClock clock;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MeteringHandlerTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
    }

    /** A formatting with ONLY the summation pair + unit read. */
    private static MeteringFormatting summation(int multiplier, int divisor,
            Integer unit) {
        return new MeteringFormatting(0, 0, 0, 0, 0, 0, multiplier, divisor, unit);
    }

    private List<NormalizedAttribute> normalize(MeteringFormatting formatting,
            Map<Integer, Object> attributes) {
        return new MeteringHandler(DEVICE, clock, formatting)
                .normalize(1, MeteringHandler.CLUSTER_ID, attributes);
    }

    @Test
    @DisplayName("divisor 1,000 (1 raw = 1 Wh): 12,345 raw → 12,345.0 Wh — raw and "
            + "formatting retained")
    void divisorOneThousand() {
        List<NormalizedAttribute> reports = normalize(summation(1, 1_000, 0x00),
                Map.of(0x0000, 12_345L));

        assertThat(reports).hasSize(1);
        NormalizedAttribute report = reports.get(0);
        assertThat(report.attributeKey()).isEqualTo("energy_wh");
        assertThat(report.value()).isEqualTo(12_345.0);
        assertThat(report.unit()).isEqualTo("Wh");
        assertThat(report.rawProtocolValue()).isEqualTo("12345");
        assertThat(report.rawProtocolUnit())
                .isEqualTo("mult=1 div=1000 unit=0x0");
    }

    @Test
    @DisplayName("divisor 1,000,000 (1 raw = 1 mWh): 12,345,678 raw → 12,345.678 Wh")
    void divisorOneMillion() {
        assertThat(normalize(summation(1, 1_000_000, 0x00),
                Map.of(0x0000, 12_345_678L)).get(0).value())
                .isEqualTo(12_345.678);
    }

    @Test
    @DisplayName("divisor 3,600,000 (1 raw = 1 Ws — the TR3 per z2m): 3,600,000 raw "
            + "→ 1,000.0 Wh; 18,000 raw → 5.0 Wh")
    void divisorThreePointSixMillion() {
        assertThat(normalize(summation(1, 3_600_000, 0x00),
                Map.of(0x0000, 3_600_000L)).get(0).value()).isEqualTo(1_000.0);
        assertThat(normalize(summation(1, 3_600_000, 0x00),
                Map.of(0x0000, 18_000L)).get(0).value()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("the multiplier is applied too: 250 raw × 4 / 1000 kWh = 1,000 Wh")
    void multiplierApplies() {
        assertThat(normalize(summation(4, 1_000, 0x00),
                Map.of(0x0000, 250L)).get(0).value()).isEqualTo(1_000.0);
    }

    @Test
    @DisplayName("a uint48 above the int range scales without overflow: "
            + "5,000,000,000 raw ÷ 1,000,000 → 5,000,000.0 Wh")
    void uint48BeyondIntRangeScales() {
        assertThat(normalize(summation(1, 1_000_000, 0x00),
                Map.of(0x0000, 5_000_000_000L)).get(0).value())
                .isEqualTo(5_000_000.0);
    }

    @Test
    @DisplayName("a unit other than kWh emits NOTHING and one DEBUG names the unit "
            + "— no guess (0x01 = m³)")
    void nonKilowattHourUnitIsSkippedWithDebug() {
        Logger handlerLogger =
                (Logger) LoggerFactory.getLogger(MeteringHandler.class);
        Level previous = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        handlerLogger.addAppender(capture);
        List<NormalizedAttribute> reports;
        try {
            reports = normalize(summation(1, 1_000, 0x01),
                    Map.of(0x0000, 12_345L));
        } finally {
            handlerLogger.detachAppender(capture);
            handlerLogger.setLevel(previous);
        }

        assertThat(reports).isEmpty();
        assertThat(capture.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage).toList())
                .containsExactly("zigbee.metering_unit_unsupported: device="
                        + "0x00124B00AA0000E2 endpoint=1 unit=0x1; summation not "
                        + "scaled (only kWh 0x0 is) — nothing emitted");
    }

    @Test
    @DisplayName("an UNREAD unit is not assumed to be kWh — nothing emitted")
    void unreadUnitIsNeverAssumed() {
        assertThat(normalize(summation(1, 1_000, null),
                Map.of(0x0000, 12_345L))).isEmpty();
    }

    @Test
    @DisplayName("the uint48 invalid sentinel (0xFFFFFFFFFFFF) and a negative "
            + "value emit nothing")
    void invalidSentinelNeverObserves() {
        assertThat(normalize(summation(1, 1_000, 0x00),
                Map.of(0x0000, 0xFFFF_FFFF_FFFFL))).isEmpty();
        assertThat(normalize(summation(1, 1_000, 0x00),
                Map.of(0x0000, -1L))).isEmpty();
    }

    @Test
    @DisplayName("the emitted key IS the energyMeter capability's schema key")
    void emittedKeyMatchesTheCapabilitySchema() {
        String key = normalize(summation(1, 1_000, 0x00),
                Map.of(0x0000, 12_345L)).get(0).attributeKey();

        assertThat(StandardCapabilities.energyMeter().attributeSchemas())
                .containsKey(key);
    }

    @Test
    @DisplayName("an attribute the handler does not map (InstantaneousDemand "
            + "0x0400 — int24, undecoded, out of scope) → empty; malformed → empty")
    void unmappedAndMalformedEmitNothing() {
        assertThat(normalize(summation(1, 1_000, 0x00),
                Map.of(0x0400, 80L))).isEmpty();
        assertThat(normalize(summation(1, 1_000, 0x00),
                Map.of(0x0000, "lots"))).isEmpty();
    }

    @Test
    @DisplayName("reset_meter has no command path in this lane — buildCommand "
            + "throws the template's UnsupportedOperationException")
    void resetMeterIsUnsupported() {
        MeteringHandler handler =
                new MeteringHandler(DEVICE, clock, summation(1, 1_000, 0x00));

        assertThatThrownBy(() -> handler.buildCommand("reset_meter", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("MeteringHandler")
                .hasMessageContaining("reset_meter");
    }

    @Test
    @DisplayName("constructed WITH a formatting — never without")
    void constructionRequiresAFormatting() {
        assertThatThrownBy(() -> new MeteringHandler(DEVICE, clock, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the formatting read is ONE frame of four attributes (the "
            + "BENCH-VERIFY ids, bound by code and test together)")
    void formattingAttributesAreTheFourSummationFormattingIds() {
        assertThat(MeteringHandler.formattingAttributes())
                .containsExactly(0x0300, 0x0301, 0x0302, 0x0303);
    }
}
