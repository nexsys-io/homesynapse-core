/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AVAIL-API-1 E-4 (AMD-103 R-C) — the availability contract the tracker declares: the class
 * NAMES the arm the tracker already takes (passive unless the ZCL PowerSource is a mains class;
 * {@code mains-metered} when the reporting-contract lookup yields a maximum, else
 * {@code mains-floor}); the silence limit, the report interval, {@code missesToDark} and the
 * record interval ride with it. Declared for every known device at construction, at every
 * (re-)interview ({@link StandardAvailabilityTracker#declareContract}), at every evaluation
 * whose derived contract differs from the last declared one, and after 30 days on the
 * monotonic source. A throwing lookup declares nothing that cycle and never stops the sweep.
 */
@DisplayName("StandardAvailabilityTracker — the availability contract (AVAIL-API-1 E-4, AMD-103 R-C)")
final class StandardAvailabilityTrackerContractTest {

    private static final IEEEAddress METERED = new IEEEAddress(0x8CF681FFFE2E0001L);
    private static final IEEEAddress THREE_PHASE = new IEEEAddress(0x00158D0001AB34EFL);
    private static final IEEEAddress FLOOR = new IEEEAddress(0x0017880109AB12CDL);
    private static final IEEEAddress BATTERY = new IEEEAddress(0x00124B0012345678L);
    private static final IEEEAddress BATTERY_QUIET = new IEEEAddress(0x00124B00DEADBEEFL);
    private static final IEEEAddress DC = new IEEEAddress(0x00124B00AA55AA55L);
    private static final IEEEAddress UNKNOWN = new IEEEAddress(0x8CF681FFFE12AB34L);
    private static final Optional<LinkReading> ANY_LINK = Optional.of(new LinkReading(164, -59));

    private TestClock clock;
    private long nanos;
    private Map<Long, Integer> powerSources;
    private Map<Long, Duration> contracts;
    private Map<Long, Duration> intervals;
    private Set<Long> throwingContractLookups;
    private List<Declared> declared;
    private StandardAvailabilityTracker tracker;
    private Instant constructedAt;

    /** One declaration as the listener received it. */
    record Declared(IEEEAddress device, Instant at, AvailabilityContractDeclaredEvent contract) { }

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StandardAvailabilityTrackerContractTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        nanos = 1_000_000_000L;
        powerSources = new HashMap<>();
        powerSources.put(METERED.value(), 0x01);
        powerSources.put(THREE_PHASE.value(), 0x02);
        powerSources.put(FLOOR.value(), 0x01);
        powerSources.put(BATTERY.value(), 0x03);
        powerSources.put(BATTERY_QUIET.value(), 0x03);
        powerSources.put(DC.value(), 0x04);
        powerSources.put(UNKNOWN.value(), 0x00);
        contracts = new HashMap<>();
        contracts.put(METERED.value(), Duration.ofSeconds(600));
        contracts.put(THREE_PHASE.value(), Duration.ofSeconds(1200));
        intervals = new HashMap<>();
        intervals.put(BATTERY.value(), Duration.ofSeconds(7200));
        throwingContractLookups = new HashSet<>();
        declared = new ArrayList<>();
    }

    private void createTracker(Map<Long, StandardAvailabilityTracker.Seed> seeds) {
        constructedAt = clock.instant();
        tracker = new StandardAvailabilityTracker(clock, () -> nanos,
                device -> powerSources.getOrDefault(device.value(), 0),
                device -> Optional.ofNullable(intervals.get(device.value())),
                device -> {
                    if (throwingContractLookups.contains(device.value())) {
                        throw new IllegalStateException("cache unavailable");
                    }
                    return Optional.ofNullable(contracts.get(device.value()));
                },
                seeds,
                new StandardAvailabilityTracker.TransitionListener() {
                    @Override
                    public void onTransition(IEEEAddress device, Instant instant,
                            boolean available, AvailabilityReason reason, Instant lastSeen,
                            LinkReading lastLink, Instant lastLinkAt) {
                        // not under test here
                    }

                    @Override
                    public void onProbeRecord(IEEEAddress device, Instant at,
                            Instant windowStart, int answered, int missed,
                            LinkReading lastLink, Instant lastLinkAt) {
                        // not under test here
                    }

                    @Override
                    public void onContractDeclared(IEEEAddress device, Instant at,
                            AvailabilityContractDeclaredEvent contract) {
                        declared.add(new Declared(device, at, contract));
                    }
                });
    }

    private void advance(Duration duration) {
        clock.advance(duration);
        nanos += duration.toNanos();
    }

    private static AvailabilityContractDeclaredEvent contract(String availabilityClass,
            Integer reportInterval, int silenceLimit, Integer recordInterval) {
        return new AvailabilityContractDeclaredEvent(availabilityClass, reportInterval,
                silenceLimit, StandardAvailabilityTracker.PROBE_MISSES_TO_DARK, recordInterval);
    }

    private List<AvailabilityContractDeclaredEvent> declaredFor(IEEEAddress device) {
        return declared.stream().filter(d -> d.device().equals(device)).map(Declared::contract)
                .toList();
    }

    @Test
    @DisplayName("the constants: the refresh is 30 days; the record interval 3600 s")
    void constants() {
        assertThat(StandardAvailabilityTracker.CONTRACT_REFRESH).isEqualTo(Duration.ofDays(30));
        assertThat(StandardAvailabilityTracker.RECORD_INTERVAL).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("E-4 (a): the class per PowerSource and lookups — 0x01/0x02 with a maximum → "
            + "mains-metered (maximum + 60 s, asked, recorded hourly); 0x01 without → mains-floor "
            + "(60 s); a non-mains source → passive with the declared interval, else 25 h")
    void classPerPowerSourceAndLookups() {
        createTracker(Map.of());

        tracker.declareContract(METERED);
        tracker.declareContract(THREE_PHASE);
        tracker.declareContract(FLOOR);
        tracker.declareContract(BATTERY);
        tracker.declareContract(BATTERY_QUIET);
        tracker.declareContract(DC);
        tracker.declareContract(UNKNOWN);

        assertThat(declaredFor(METERED))
                .containsExactly(contract("mains-metered", 600, 660, 3600));
        assertThat(declaredFor(THREE_PHASE))
                .containsExactly(contract("mains-metered", 1200, 1260, 3600));
        assertThat(declaredFor(FLOOR))
                .containsExactly(contract("mains-floor", null, 60, 3600));
        assertThat(declaredFor(BATTERY))
                .containsExactly(contract("passive", 7200, 7200, null));
        assertThat(declaredFor(BATTERY_QUIET))
                .containsExactly(contract("passive", null, 90_000, null));
        assertThat(declaredFor(DC))
                .as("N-5: an exotic source takes the passive arm")
                .containsExactly(contract("passive", null, 90_000, null));
        assertThat(declaredFor(UNKNOWN))
                .as("N-5: UNKNOWN takes the passive arm")
                .containsExactly(contract("passive", null, 90_000, null));
        assertThat(declared).extracting(Declared::at).containsOnly(clock.instant());
        assertThat(tracker.declaredContract(METERED))
                .contains(contract("mains-metered", 600, 660, 3600));
    }

    @Test
    @DisplayName("E-4 (b): every known device is declared at construction — the seeded devices, "
            + "an UNAVAILABLE one included, each once, at the construction instant")
    void declaredForEveryKnownDeviceAtConstruction() {
        createTracker(Map.of(
                METERED.value(), new StandardAvailabilityTracker.Seed(true, clock.instant()),
                FLOOR.value(), new StandardAvailabilityTracker.Seed(false, null),
                BATTERY.value(), new StandardAvailabilityTracker.Seed(null, null)));

        assertThat(declared).hasSize(3);
        assertThat(declared).extracting(Declared::device)
                .containsExactlyInAnyOrder(METERED, FLOOR, BATTERY);
        assertThat(declared).extracting(Declared::at).containsOnly(constructedAt);
        assertThat(declaredFor(FLOOR))
                .as("a seeded-dark device is still declared (R-C: every device the tracker knows)")
                .containsExactly(contract("mains-floor", null, 60, 3600));
        assertThat(tracker.declaredContract(BATTERY)).contains(contract("passive", 7200, 7200, null));
        assertThat(tracker.declaredContract(DC)).as("an unknown device has no contract").isEmpty();
    }

    @Test
    @DisplayName("E-4 (c): re-declared on a lookup change (a maximum appears: floor → metered) and "
            + "NOT re-declared on an unchanged evaluation")
    void redeclaredOnChange_notOnAnUnchangedEvaluation() {
        createTracker(Map.of());
        tracker.recordFrame(FLOOR, clock.instant(), ANY_LINK);

        tracker.evaluateTimeouts();
        assertThat(declaredFor(FLOOR))
                .as("the first evaluation declares a device the tracker learned by a frame")
                .containsExactly(contract("mains-floor", null, 60, 3600));

        advance(Duration.ofSeconds(1));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(FLOOR)).as("unchanged: nothing declared").hasSize(1);

        contracts.put(FLOOR.value(), Duration.ofSeconds(600));
        advance(Duration.ofSeconds(1));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(FLOOR)).containsExactly(
                contract("mains-floor", null, 60, 3600),
                contract("mains-metered", 600, 660, 3600));
        assertThat(tracker.declaredContract(FLOOR))
                .contains(contract("mains-metered", 600, 660, 3600));

        contracts.remove(FLOOR.value());
        advance(Duration.ofSeconds(1));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(FLOOR))
                .as("a contract removed at runtime: back to the floor, declared again")
                .hasSize(3);
        assertThat(declaredFor(FLOOR).get(2)).isEqualTo(contract("mains-floor", null, 60, 3600));
    }

    @Test
    @DisplayName("E-4 (d): re-declared when 30 days have passed on the monotonic source since the "
            + "device's last contract record — the same tuple, a new row; not at 30 days less 1 s")
    void redeclaredAfterThirtyDaysOnTheMonotonicSource() {
        createTracker(Map.of(
                METERED.value(), new StandardAvailabilityTracker.Seed(true, clock.instant())));
        assertThat(declaredFor(METERED)).hasSize(1);

        advance(Duration.ofDays(30).minusSeconds(1));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(METERED)).as("29 d 23 h 59 m 59 s: not yet").hasSize(1);

        advance(Duration.ofSeconds(2));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(METERED)).hasSize(2);
        assertThat(declaredFor(METERED).get(1)).isEqualTo(declaredFor(METERED).get(0));
        assertThat(declared.get(1).at()).isEqualTo(clock.instant());

        advance(Duration.ofSeconds(1));
        tracker.evaluateTimeouts();
        assertThat(declaredFor(METERED)).as("the refresh restarts the 30 days").hasSize(2);
    }

    @Test
    @DisplayName("E-4 (e): a throwing lookup declares nothing for that device that cycle, warns "
            + "once (the existing per-cycle WARN) and does not break the sweep — the other device "
            + "is declared; the next cycle, the lookup repaired, declares it")
    void throwingLookup_declaresNothingThatCycle_sweepContinues() {
        Logger logger = (Logger) LoggerFactory.getLogger(StandardAvailabilityTracker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            createTracker(Map.of());
            tracker.recordFrame(METERED, clock.instant(), ANY_LINK);
            tracker.recordFrame(FLOOR, clock.instant(), ANY_LINK);
            throwingContractLookups.add(METERED.value());

            tracker.evaluateTimeouts();

            assertThat(declaredFor(METERED)).isEmpty();
            assertThat(tracker.declaredContract(METERED)).isEmpty();
            assertThat(declaredFor(FLOOR)).containsExactly(contract("mains-floor", null, 60, 3600));
            List<String> warnings = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("zigbee.availability_limit_lookup_failed:"))
                    .toList();
            assertThat(warnings).as("one WARN per device per cycle — the contract pass adds none")
                    .hasSize(1);
            assertThat(warnings.get(0)).contains("device=" + METERED);

            throwingContractLookups.clear();
            advance(Duration.ofSeconds(1));
            tracker.evaluateTimeouts();

            assertThat(declaredFor(METERED)).containsExactly(contract("mains-metered", 600, 660, 3600));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("E-4 (f): declareContract (the (re-)interview arm) declares even when unchanged — "
            + "a row per interview, each true when written")
    void declareContractAtInterview_declaresEvenWhenUnchanged() {
        createTracker(Map.of());
        tracker.recordFrame(FLOOR, clock.instant(), ANY_LINK);
        tracker.evaluateTimeouts();
        assertThat(declaredFor(FLOOR)).hasSize(1);

        tracker.declareContract(FLOOR);

        assertThat(declaredFor(FLOOR)).hasSize(2);
        assertThat(declaredFor(FLOOR).get(1)).isEqualTo(declaredFor(FLOOR).get(0));
    }

    @Test
    @DisplayName("E-4 (g): a seeded-UNAVAILABLE device is skipped by the timeout sweep but not "
            + "by the contract pass — a contract change reaches it")
    void unavailableDevice_stillGetsContractChanges() {
        createTracker(Map.of(FLOOR.value(), new StandardAvailabilityTracker.Seed(false, null)));
        assertThat(declaredFor(FLOOR)).containsExactly(contract("mains-floor", null, 60, 3600));

        contracts.put(FLOOR.value(), Duration.ofSeconds(900));
        advance(Duration.ofSeconds(1));
        assertThat(tracker.evaluateTimeouts()).as("a dark device is never pinged").isEmpty();

        assertThat(declaredFor(FLOOR)).hasSize(2);
        assertThat(declaredFor(FLOOR).get(1)).isEqualTo(contract("mains-metered", 900, 960, 3600));
        assertThat(tracker.isAvailable(FLOOR)).isFalse();
    }
}
