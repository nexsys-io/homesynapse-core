/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.NonFiringExplanation;
import com.homesynapse.automation.RunId;
import com.homesynapse.automation.RunPage;
import com.homesynapse.device.Entity;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * MEASURE-2b (T7) — the READ PATH at run-scale row counts on a REAL SQLite store.
 * For each N of the knob: boot {@link RealCoreFixture} on a fresh temp dir, adopt
 * the metering plug, seed the store to N rows through the scripted rig's
 * {@code ActivePower} reports with one confirmed hero loop per 1,000 rows, then time
 * the hero's six reads warm (k = {@value #WARM_READS}), restart the core on the same
 * store, time the restart and the six reads again cold (k = 1). One greppable line
 * per N:
 * <pre>
 * MEASURE2B knob=&lt;env|default&gt; sha=d1c2cbc clock_step_ms=&lt;n&gt; k=5 profile=testing seed_cap_min=&lt;n&gt;
 * MEASURE2B rows=&lt;n&gt; runs=&lt;r&gt; seed_events_per_s=&lt;f&gt; db_bytes=&lt;b&gt; wal_bytes=&lt;b&gt;
 *   shm_bytes=&lt;b&gt; bytes_per_row=&lt;f&gt; q1_ms=&lt;min/med/max&gt; … q6_ms=… restart_ms=&lt;n&gt;
 *   q1_cold_ms=&lt;n&gt; … q6_cold_ms=&lt;n&gt; target=&lt;N&gt; reports=&lt;n&gt; seed_wall_s=&lt;f&gt;
 *   clock_end=&lt;instant&gt; entities=&lt;n&gt; q2_verdict=&lt;warm&gt;/&lt;cold&gt;
 *   hero_id_stable=&lt;bool&gt; capped=&lt;bool&gt;          (ONE line; wrapped here only)
 * </pre>
 *
 * <p><strong>The six reads</strong> — the services the dashboard's endpoints call,
 * obtained the way the composition root obtains them
 * ({@code ExplanationService.over(eventStore, automationRegistry)}), never HTTP:
 * q1 {@code explainRun(lastRunId)} · q2 {@code explainNonFiring(hero, 0L)} — 0 is
 * "since the beginning", the widest walk · q3 {@code readByCorrelation} of the last
 * run's correlation · q4 {@code listRuns(empty, MAX, 20)} · q5
 * {@code listAutomations()} · q6 the entities list's read —
 * {@code StateQueryService.getSnapshot()} joined per row with
 * {@code EntityRegistry.findEntity}, sorted and limited as
 * {@code ListEntitiesEndpoint.apply} does.
 *
 * <p><strong>A measurement, neither red nor green.</strong> It FAILS only when the
 * harness cannot seed, read or restart: the first seeded report is a
 * {@code state_reported} carrying {@code power_w}; the store holds N rows after
 * seeding (unless the 10-minute seeding cap cut it — then the line says
 * {@code capped=true}); every read returns non-empty at N ≥ 2,000; the restart
 * reaches subscribers-live. A slow read is a NUMBER. A read that throws prints
 * {@code ERR(<type>)} in its field, the line still prints, and the failure is raised
 * after the last N.
 *
 * <p><strong>The knob</strong> is an ENVIRONMENT variable — {@value #ROWS_ENV}, a
 * comma list of N, default {@value #DEFAULT_ROWS} (the gate's smoke) — because a
 * {@code -D} on the gradlew line never reaches the forked test JVM.
 * {@value #CLOCK_STEP_ENV} (default {@value #DEFAULT_CLOCK_STEP_MS}) is the domain
 * time one pumped batch of {@value RealCoreFixture#SEED_BATCH} reports spans;
 * {@code 0} freezes the clock — then every event shares one instant and
 * {@code explainRun}'s hint window holds the whole log (the rig's artifact, kept
 * reachable so it can be shown, never the default). {@value #SEED_CAP_ENV} (default
 * {@value #DEFAULT_SEED_CAP_MINUTES}) is the seeding cap per N in minutes: the seed
 * is paced by production's own derived-write token bucket (AMD-43 §3.6.4 — 200
 * {@code state_changed}/s; every alternating report is a state change, and the
 * ingestion dedup drops a constant one), about 400 rows/s sustained.
 *
 * <p><strong>Cold</strong> means a new core's first read: new SQLite connections
 * with an empty page cache. The OS file cache and the JIT stay warm. The profile is
 * {@code HomeSynapseConfig.testing()} — cache 2 MB, mmap 32 MB, one read thread
 * (HOME: 16 MB, 256 MB, two) — the boot shape's own.
 */
@DisplayName("MeasureReadPathIT — MEASURE-2b: the hero's six reads timed at run-scale "
        + "row counts on a real SQLite store; a slow read is a number, never a failure")
final class MeasureReadPathIT {

    static final String ROWS_ENV = "HOMESYNAPSE_MEASURE2B_ROWS";
    static final String CLOCK_STEP_ENV = "HOMESYNAPSE_MEASURE2B_CLOCK_STEP_MS";
    static final String SEED_CAP_ENV = "HOMESYNAPSE_MEASURE2B_SEED_CAP_MIN";
    static final String DEFAULT_ROWS = "2000";
    static final long DEFAULT_CLOCK_STEP_MS = 1_000L;
    static final long DEFAULT_SEED_CAP_MINUTES = 10L;
    static final String BASELINE_SHA = "d1c2cbc";
    static final int WARM_READS = 5;

    private static final int ROWS_PER_PULSE = 1_000;
    /** Raw {@code ActivePower}; the rig's divisor 100 renders 80.00 / 80.01 W. */
    private static final int WATTS0 = 8_000;
    private static final long READ_CAP_NANOS = Duration.ofSeconds(60).toNanos();
    private static final String HERO_SLUG = "hero-motion";
    private static final String NOT_AVAILABLE = "na";

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MeasureReadPathIT() {
    }

    @Test
    @DisplayName("T7: per N — seed, six reads warm (k=5), restart, six reads cold (k=1) "
            + "→ one MEASURE2B line; harness assertions only")
    void readPath_atRunScaleRowCounts(@TempDir Path root) throws Exception {
        String knob = environment(ROWS_ENV, DEFAULT_ROWS);
        long clockStepMs = Long.parseLong(
                environment(CLOCK_STEP_ENV, Long.toString(DEFAULT_CLOCK_STEP_MS)));
        long seedCapMinutes = Long.parseLong(
                environment(SEED_CAP_ENV, Long.toString(DEFAULT_SEED_CAP_MINUTES)));
        System.out.println("MEASURE2B knob=" + knob + " sha=" + BASELINE_SHA
                + " clock_step_ms=" + clockStepMs + " k=" + WARM_READS + " profile=testing"
                + " seed_cap_min=" + seedCapMinutes);

        List<String> harnessFailures = new ArrayList<>();
        for (int target : targets(knob)) {
            measure(target, Duration.ofMillis(clockStepMs),
                    Duration.ofMinutes(seedCapMinutes).toNanos(),
                    Files.createDirectory(root.resolve("n" + target)), harnessFailures);
        }
        assertThat(harnessFailures)
                .as("MEASURE-2b harness failures (seed · read · restart) — never a timing")
                .isEmpty();
    }

    private static void measure(int target, Duration clockStep, long seedCapNanos,
            Path dir, List<String> harnessFailures) throws Exception {
        RealCoreFixture fixture = RealCoreFixture.boot(dir,
                RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml()));
        try {
            Map<Integer, EntityId> plug = fixture.adoptGen4();

            // R2: the first seeded report is a state_reported carrying power_w on the
            // plug's entity — read from the store before seeding to N.
            long beforeFirst = fixture.rows();
            long seedNanos = fixture.seedPowerReports(1, WATTS0, clockStep,
                    MeasureReadPathIT::stopwatchNanos);
            long rowsPerReport = Math.max(1L, fixture.rows() - beforeFirst);
            assertFirstReportIsPower(fixture, beforeFirst, plug.values());

            long seededRows = fixture.rows() - beforeFirst;
            long reports = 1;
            int runs = 0;
            long lastPulseStart = 0L;
            long nextPulseAt = ROWS_PER_PULSE;
            boolean capped = false;
            long seedingStart = stopwatchNanos();
            while (fixture.rows() < target) {
                long head = fixture.rows();
                int chunk = (int) Math.max(1L,
                        (Math.min(target, nextPulseAt) - head) / rowsPerReport);
                seedNanos += fixture.seedPowerReports(chunk, WATTS0, clockStep,
                        MeasureReadPathIT::stopwatchNanos);
                seededRows += fixture.rows() - head;
                reports += chunk;
                if (fixture.rows() >= nextPulseAt) {
                    lastPulseStart = fixture.rows();
                    fixture.pulseMotion();
                    runs++;
                    nextPulseAt += ROWS_PER_PULSE;
                }
                if (stopwatchNanos() - seedingStart > seedCapNanos) {
                    capped = true;
                    break;
                }
            }
            if (runs == 0) {
                lastPulseStart = fixture.rows();
                fixture.pulseMotion();
                runs++;
            }
            fixture.settle();

            long rows = fixture.rows();
            long dbBytes = fixture.dbBytes();
            long walBytes = fixture.walBytes();
            long shmBytes = fixture.shmBytes();
            if (!capped && rows < target) {
                harnessFailures.add("rows=" + rows + " < target=" + target);
            }

            Reads warm = Reads.over(fixture, lastPulseStart);
            List<Timing> warmTimings = warm.time(WARM_READS);
            String warmVerdict = warm.nonFiringVerdict();

            String restartMs;
            List<Timing> coldTimings = List.of();
            String coldVerdict = NOT_AVAILABLE;
            String heroIdStable = NOT_AVAILABLE;
            try {
                long restartNanos = fixture.restart(MeasureReadPathIT::stopwatchNanos);
                restartMs = String.format(Locale.ROOT, "%.0f", restartNanos / 1_000_000.0);
                Reads cold = warm.onRestarted(fixture);
                coldTimings = cold.time(1);
                coldVerdict = cold.nonFiringVerdict();
                heroIdStable = Boolean.toString(cold.hero.equals(warm.hero));
            } catch (Exception | Error restartFailure) {
                restartMs = "FAILED(" + restartFailure.getClass().getSimpleName() + ")";
                harnessFailures.add("target=" + target + " restart: " + restartFailure);
            }

            StringBuilder line = new StringBuilder(640);
            line.append("MEASURE2B rows=").append(rows)
                    .append(" runs=").append(runs)
                    .append(String.format(Locale.ROOT, " seed_events_per_s=%.1f",
                            seededRows / (seedNanos / 1_000_000_000.0)))
                    .append(" db_bytes=").append(dbBytes)
                    .append(" wal_bytes=").append(walBytes)
                    .append(" shm_bytes=").append(shmBytes)
                    .append(String.format(Locale.ROOT, " bytes_per_row=%.1f",
                            (dbBytes + walBytes + shmBytes) / (double) rows));
            for (int q = 0; q < Reads.COUNT; q++) {
                line.append(" q").append(q + 1).append("_ms=")
                        .append(warmTimings.get(q).minMedMax());
            }
            line.append(" restart_ms=").append(restartMs);
            for (int q = 0; q < Reads.COUNT; q++) {
                line.append(" q").append(q + 1).append("_cold_ms=")
                        .append(coldTimings.isEmpty()
                                ? NOT_AVAILABLE : coldTimings.get(q).single());
            }
            line.append(" target=").append(target)
                    .append(" reports=").append(reports)
                    .append(String.format(Locale.ROOT, " seed_wall_s=%.3f",
                            seedNanos / 1_000_000_000.0))
                    .append(" clock_end=").append(fixture.clock().peek())
                    .append(" entities=").append(warm.entityRows)
                    .append(" q2_verdict=").append(warmVerdict).append('/').append(coldVerdict)
                    .append(" hero_id_stable=").append(heroIdStable)
                    .append(" capped=").append(capped);
            System.out.println(line);

            if (target >= 2_000) {
                collectReadFailures("warm", target, warmTimings, harnessFailures);
                collectReadFailures("cold", target, coldTimings, harnessFailures);
            }
        } finally {
            fixture.close();
        }
    }

    // ── the six reads ───────────────────────────────────────────────────────

    /** The six reads bound to one booted core; {@link #time} runs each k times. */
    private static final class Reads {

        static final int COUNT = 6;

        private final HomeSynapseCore core;
        private final ExplanationService explain;
        private final AutomationId hero;
        private final RunId lastRun;
        private final Ulid lastCorrelation;
        private int entityRows;

        private Reads(HomeSynapseCore core, RunId lastRun, Ulid lastCorrelation) {
            this.core = core;
            // The composition root's own construction (HomeSynapseCore:1051–:1052).
            this.explain = ExplanationService.over(core.eventStore(),
                    core.automationRegistry());
            this.hero = core.automationRegistry().getBySlug(HERO_SLUG)
                    .orElseThrow(() -> new AssertionError("'" + HERO_SLUG + "' not loaded"))
                    .automationId();
            this.lastRun = lastRun;
            this.lastCorrelation = lastCorrelation;
        }

        /** The last run's id from {@code listRuns(…, 1)}; its correlation from its terminal marker. */
        static Reads over(RealCoreFixture fixture, long lastPulseStart) {
            HomeSynapseCore core = fixture.core();
            RunId lastRun = ExplanationService
                    .over(core.eventStore(), core.automationRegistry())
                    .listRuns(Optional.empty(), Long.MAX_VALUE, 1).runs().stream()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no terminal run in the log"))
                    .runId();
            Ulid correlation = core.eventStore()
                    .readByType(EventTypes.AUTOMATION_COMPLETED, lastPulseStart, 10)
                    .events().stream()
                    .filter(event -> ((AutomationCompletedEvent) event.payload()).runId()
                            .equals(lastRun.value()))
                    .map(event -> event.causalContext().correlationId())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the last run's automation_completed is not past position "
                                    + lastPulseStart));
            return new Reads(core, lastRun, correlation);
        }

        /** The same run and correlation over the restarted core; the hero id re-resolved by slug. */
        Reads onRestarted(RealCoreFixture fixture) {
            return new Reads(fixture.core(), lastRun, lastCorrelation);
        }

        List<Timing> time(int k) {
            List<Timing> timings = new ArrayList<>(COUNT);
            timings.add(Timing.of(k, () -> explain.explainRun(lastRun).isPresent()));
            timings.add(Timing.of(k, () -> explain.explainNonFiring(hero, 0L).isPresent()));
            timings.add(Timing.of(k,
                    () -> !core.eventStore().readByCorrelation(lastCorrelation).isEmpty()));
            timings.add(Timing.of(k, () -> {
                RunPage page = explain.listRuns(Optional.empty(), Long.MAX_VALUE, 20);
                return !page.runs().isEmpty();
            }));
            timings.add(Timing.of(k, () -> !explain.listAutomations().isEmpty()));
            timings.add(Timing.of(k, () -> {
                entityRows = entitiesList();
                return entityRows > 0;
            }));
            return timings;
        }

        /** {@code ListEntitiesEndpoint.apply}'s reads: the snapshot, sorted, limited, joined per row. */
        private int entitiesList() {
            StateSnapshot snapshot = core.stateQueryService().getSnapshot();
            List<String> deviceIds = new ArrayList<>();
            snapshot.states().values().stream()
                    .sorted(Comparator.comparing((EntityState state) ->
                            state.entityId().toString()))
                    .limit(50)
                    .forEach(state -> deviceIds.add(core.entityRegistry()
                            .findEntity(state.entityId())
                            .map(Entity::deviceId)
                            .map(Object::toString)
                            .orElse(null)));
            return deviceIds.size();
        }

        String nonFiringVerdict() {
            try {
                return explain.explainNonFiring(hero, 0L)
                        .map(NonFiringExplanation::verdict)
                        .map(Enum::name)
                        .orElse("UNKNOWN_AUTOMATION");
            } catch (RuntimeException failure) {
                return "ERR(" + failure.getClass().getSimpleName() + ")";
            }
        }
    }

    /**
     * One read's samples, ascending.
     *
     * @param nanos    the completed iterations' wall times, sorted
     * @param nonEmpty every completed iteration returned a non-empty result
     * @param error    the throwable's simple name when an iteration threw, else null
     * @param cut      the 60 s per-read cap stopped the iterations early
     */
    private record Timing(long[] nanos, boolean nonEmpty, String error, boolean cut) {

        static Timing of(int k, BooleanSupplier read) {
            long[] samples = new long[k];
            boolean nonEmpty = true;
            long total = 0L;
            int done = 0;
            while (done < k && total <= READ_CAP_NANOS) {
                long start = stopwatchNanos();
                try {
                    nonEmpty &= read.getAsBoolean();
                } catch (RuntimeException failure) {
                    return new Timing(new long[0], false,
                            failure.getClass().getSimpleName(), false);
                }
                samples[done] = stopwatchNanos() - start;
                total += samples[done];
                done++;
            }
            long[] completed = Arrays.copyOf(samples, done);
            Arrays.sort(completed);
            return new Timing(completed, nonEmpty, null, done < k);
        }

        String minMedMax() {
            if (error != null) {
                return "ERR(" + error + ")";
            }
            return String.format(Locale.ROOT, "%.3f/%.3f/%.3f%s",
                    millis(nanos[0]), millis(nanos[nanos.length / 2]),
                    millis(nanos[nanos.length - 1]), cut ? "(k=" + nanos.length + ")" : "");
        }

        String single() {
            return error != null ? "ERR(" + error + ")"
                    : String.format(Locale.ROOT, "%.3f", millis(nanos[0]));
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private static void collectReadFailures(String phase, int target, List<Timing> timings,
            List<String> harnessFailures) {
        for (int q = 0; q < timings.size(); q++) {
            Timing timing = timings.get(q);
            if (timing.error() != null) {
                harnessFailures.add("target=" + target + " " + phase + " q" + (q + 1)
                        + " threw " + timing.error());
            } else if (!timing.nonEmpty()) {
                harnessFailures.add("target=" + target + " " + phase + " q" + (q + 1)
                        + " returned empty");
            }
        }
    }

    private static void assertFirstReportIsPower(RealCoreFixture fixture, long afterPosition,
            Collection<EntityId> plugEntities) {
        List<EventEnvelope> reported = fixture.core().eventStore()
                .readByType(EventTypes.STATE_REPORTED, afterPosition, 10).events();
        assertThat(reported)
                .as("the first seeded ActivePower report lands as state_reported — an "
                        + "empty read here is a silent metering handler (no formatting "
                        + "read at adoption), not a measurement")
                .isNotEmpty();
        EventEnvelope first = reported.get(0);
        assertThat(((StateReportedEvent) first.payload()).attributeKey()).isEqualTo("power_w");
        assertThat(plugEntities.stream().map(EntityId::value).toList())
                .as("the report's subject is the metering plug's entity")
                .contains(first.subjectRef().id());
    }

    private static List<Integer> targets(String knob) {
        List<Integer> targets = new ArrayList<>();
        for (String part : knob.split(",")) {
            int target = Integer.parseInt(part.trim());
            if (target < 1) {
                throw new IllegalArgumentException(ROWS_ENV + " entries must be >= 1, got "
                        + target);
            }
            targets.add(target);
        }
        return targets;
    }

    private static String environment(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    // MEASURE-2b stopwatch: wall time of a read; not domain time (the domain clock is the fixture's TestClock)
    private static long stopwatchNanos() {
        return System.nanoTime();
    }
}
