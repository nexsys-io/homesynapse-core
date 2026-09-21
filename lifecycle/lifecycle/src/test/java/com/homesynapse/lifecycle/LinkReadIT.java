/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * LINK-READ (T7) — the run's link instrument through the REAL core: the real
 * zigbee adapter over the scripted NCP ({@link RealCoreFixture}), whose every
 * scripted frame carries the last-hop pair LQI 176 / RSSI −56
 * ({@code ZigbeeHardwareFreeRig.incomingMessage}).
 *
 * <p>Two lines are read from the captured log, both on the adapter's logger:
 * <ul>
 *   <li>{@code zigbee.link_summary} — once per {@code LINK_SUMMARY_PERIOD} (ten
 *       minutes on the injected clock), one line per device with the frames
 *       counted since the previous summary and the last reading;</li>
 *   <li>{@code zigbee.availability_link} — the sibling of the FROZEN
 *       {@code zigbee.availability_changed} line: a battery device's silence
 *       carries its last reading and the instant of the frame that delivered
 *       it.</li>
 * </ul>
 *
 * <p>The configuration holds one manual-trigger rule and nothing fires it: no
 * command ever leaves the engine, so every frame a device delivers here is one
 * this test queued. No wall clock is read: the shared {@code TestClock} moves
 * only where this test advances it.
 */
@DisplayName("LinkReadIT — LINK-READ T7: the silence carries the last link reading; "
        + "one link_summary line per device per ten minutes")
final class LinkReadIT {

    /** The zigbee module's package logger — the adapter, the tracker, the ingestion unit. */
    private static final String ZIGBEE_LOGGERS = "com.homesynapse.integration.zigbee";

    private static final String SNZB = ieeeHex(ZigbeeHardwareFreeRig.SNZB_IEEE);
    private static final String HUE = ieeeHex(ZigbeeHardwareFreeRig.HUE_IEEE);

    private RealCoreFixture fixture;
    private ListAppender<ILoggingEvent> zigbeeLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    LinkReadIT() {
    }

    @AfterEach
    void tearDown() {
        if (zigbeeLog != null) {
            zigbeeLogger().detachAppender(zigbeeLog);
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("T7: three SNZB frames and two Hue frames — no line per frame; at +10 min "
            + "exactly one link_summary line per device with the delivered count; past "
            + "25 h the SNZB's silence line carries the reading of its last frame")
    void summaryCountsFrames_andTheSilenceCarriesTheLastReading(@TempDir Path tempDir)
            throws Exception {
        zigbeeLog = new ListAppender<>();
        zigbeeLog.start();
        zigbeeLogger().addAppender(zigbeeLog);
        fixture = RealCoreFixture.boot(tempDir, idleConfigYaml());
        ZigbeeHardwareFreeRig rig = fixture.rig();

        // ── the frames: each one carries the rig's reading (176, −56) ────────
        int linesBeforeFrames = infoAndAbove().size();
        Instant framesAt = fixture.clock().instant();
        rig.reportOccupied(true);
        rig.deliverAndCycle();
        rig.reportOccupied(false);
        rig.deliverAndCycle();
        rig.reportOccupied(true);
        rig.deliverAndCycle();
        rig.reportOnOff(true);
        rig.deliverAndCycle();
        rig.reportOnOff(false);
        rig.deliverAndCycle();

        assertThat(infoAndAbove().subList(linesBeforeFrames, infoAndAbove().size()))
                .as("the sampling rule: a frame is counted and its reading kept in "
                        + "memory — never a log line above DEBUG per frame")
                .isEmpty();
        assertThat(messages("zigbee.link_summary"))
                .as("the ten-minute gate has not opened yet")
                .isEmpty();

        // ── +10 min: the summary, once per device ────────────────────────────
        fixture.clock().advance(Duration.ofMinutes(10));
        rig.cycle();

        assertThat(messages("zigbee.link_summary"))
                .as("exactly one line per device, with the delivered frame count")
                .containsExactly(
                        "zigbee.link_summary: device=" + SNZB + " frames=3 "
                                + "last_lqi=176 last_rssi_dbm=-56 last_link_at=" + framesAt,
                        "zigbee.link_summary: device=" + HUE + " frames=2 "
                                + "last_lqi=176 last_rssi_dbm=-56 last_link_at=" + framesAt);

        // ── past the battery silence timeout: the silence carries the reading ─
        fixture.clock().advance(Duration.ofHours(25));
        rig.cycle();

        assertThat(messages("zigbee.availability_link"))
                .as("the SNZB (battery) timed out — its line names the reason and the "
                        + "reading of the last frame it delivered")
                .contains("zigbee.availability_link: device=" + SNZB + " available=false "
                        + "reason=SILENCE_TIMEOUT last_lqi=176 last_rssi_dbm=-56 "
                        + "last_link_at=" + framesAt + " frames_since_summary=0");
        assertThat(messages("zigbee.availability_changed"))
                .as("the frozen DP-8 line stays byte-identical beside its sibling")
                .contains("zigbee.availability_changed: device=" + SNZB
                        + " available=false");
        List<String> summaries = messages("zigbee.link_summary");
        assertThat(summaries.subList(2, summaries.size()))
                .as("the gate re-armed: the next period's lines count the silence "
                        + "as 0 and keep the last reading")
                .containsExactly(
                        "zigbee.link_summary: device=" + SNZB + " frames=0 "
                                + "last_lqi=176 last_rssi_dbm=-56 last_link_at=" + framesAt,
                        "zigbee.link_summary: device=" + HUE + " frames=0 "
                                + "last_lqi=176 last_rssi_dbm=-56 last_link_at=" + framesAt);
    }

    /** One rule nothing fires: the engine boots loaded and never issues a command. */
    private static String idleConfigYaml() {
        return """
                automation:
                  automations:
                    - name: "link read idle"
                      slug: "link-read-idle"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: turn_on
                """;
    }

    /** The captured zigbee lines starting with {@code token}, in order. */
    private List<String> messages(String token) {
        return List.copyOf(zigbeeLog.list).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(token))
                .toList();
    }

    /** Every captured zigbee line at INFO or above, in order. */
    private List<String> infoAndAbove() {
        return List.copyOf(zigbeeLog.list).stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static Logger zigbeeLogger() {
        return (Logger) LoggerFactory.getLogger(ZIGBEE_LOGGERS);
    }

    private static String ieeeHex(long ieee) {
        return String.format(Locale.ROOT, "0x%016X", ieee);
    }
}
