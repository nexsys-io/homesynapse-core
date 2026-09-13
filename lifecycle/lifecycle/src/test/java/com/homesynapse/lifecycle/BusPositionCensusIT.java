/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.automation.CommandDispatchAssembly;
import com.homesynapse.automation.PendingCommandLedgerAssembly;
import com.homesynapse.device.Entity;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.bus.InProcessEventBus;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.integration.runtime.IntegrationSupervisorAssembly;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * FIX-2a (B) — TR-1b's position census, computed in-process after ONE hero loop
 * (the TR-1 §1 definition, the TR-1 §3 frozen tokens). For every subscriber the
 * composition root registers and every position the store holds:
 * {@code MATCHED(S,P)} := S's filter accepts P; {@code DELIVERED(S,P)} :=
 * {@code P <= lastDelivered(S)}; {@code MISS} := matched and not delivered.
 * BUS-ORDER-1 (2026-09-12): {@code lastDelivered} is the bus's in-memory cursor
 * ({@code InProcessEventBus.lastDelivered(id)} — the highest position delivered
 * or filtered past in this activation, AMD-101 §2), read through the concrete
 * bus; before it the census scored the PERSISTED checkpoint, which the
 * notification-ordered delivery could regress ({@code missed=1 first_missed=head}
 * on samples #11/#12 with every position delivered). The persisted checkpoint is
 * still printed ({@code checkpoint=}) beside the cursor ({@code delivered_max=}):
 * for a filtered subscriber the cursor sits ahead of it by the trailing
 * non-matching positions, by design — the checkpoint is written only after a
 * matching delivery. A miss is a matching position the cursor never passed —
 * the OR-BUS-SILENT-DROP class made countable.
 *
 * <p><strong>What is scored, and what is only reported.</strong> The DLQ is
 * visible through {@code SubscriberSnapshot} as a DEPTH only, never per position,
 * so TR-1 §1's "and no DLQ row for (S,P)" cannot be applied here: a subscriber
 * with {@code dlqDepth > 0} is printed with {@code dlq_unscored=true} and its
 * miss count is reported, not asserted (the card's driver reads the DLQ table
 * directly). {@code state_projection} registers {@code atomicCheckpoint = true}
 * (AMD-45 §2.2): its persisted checkpoint lags delivery by design, so its line
 * carries {@code atomic=true} and its own number — reported, never asserted
 * (TR-1 §1 reason 3). Every other subscriber with an empty DLQ is SCORED:
 * {@code missed=0} is the assertion.</p>
 *
 * <p><strong>The reading settles before it is scored.</strong> A delivery in
 * flight is not a miss: the census is re-read until every scored subscriber shows
 * {@code missed=0} or the hero test's window (500 × 20 ms) closes — a drop never
 * resolves inside it, an in-flight delivery resolves in milliseconds. Positions
 * come from the store (paged, never a range — retention and
 * {@code AUTOINCREMENT} make the set non-dense); the store is read BEFORE the
 * snapshots and the cursors, so a cursor can only ever be AHEAD of the position
 * set.</p>
 *
 * <p>Harness: the {@link HeroLoopHardwareFreeIT} boot shape over the
 * {@link ZigbeeHardwareFreeRig}, copied. Time is the injected {@link TestClock};
 * the await loops are real-time polls (clock-independent). The manifest, the
 * census and the token rendering are package-private statics so
 * {@link BusSoakIT} computes the same census the same way.</p>
 */
@DisplayName("BusPositionCensusIT — after one hero loop every scored subscriber's cursor covers every position its filter matches (FIX-2a B, TR-1 §1; BUS-ORDER-1 scores the in-memory cursor)")
@Tag("bus-soak")
final class BusPositionCensusIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAB"));

    /** What a {@code LongSupplier} returns when an await cannot name its position. */
    static final long NO_AWAITED_POSITION = -1L;

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId snzbEntity;
    private EntityId hueEntity;
    /** FIX-2b-i: the test's temp dir, held so a timeout's thread dump has a home. */
    private Path tempDir;
    /**
     * FIX-2b-ii (i): every {@code com.homesynapse} line, captured before boot so a
     * timeout's {@code automation.handoff_census:} reading has its source.
     */
    private ListAppender<ILoggingEvent> lineCapture;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    BusPositionCensusIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
        if (lineCapture != null) {
            homesynapseLogger().detachAppender(lineCapture);
            lineCapture.stop();
        }
    }

    @Test
    @DisplayName("one motion→On-frame→confirm loop, then bus.position_census per subscriber + bus.position_census_total: missed=0 for every scored subscriber; state_projection reported atomic=true")
    void positionCensus_afterOneHeroLoop_everyScoredSubscriberMissedZero(@TempDir Path tempDir)
            throws Exception {
        lineCapture = attachLineCapture();
        bootAndAdopt(tempDir);

        // The hero loop (HeroLoopHardwareFreeIT steps 2–4): the motion edge, the
        // On frame at the scripted NCP, the confirm.
        rig.reportOccupied(false);
        rig.deliverAndCycle();
        awaitTrue(() -> reportedCount(events(), "occupied", "false") >= 1,
                "the occupied=false baseline report");
        rig.reportOccupied(true);
        rig.deliverAndCycle();
        awaitTrue(() -> reportedCount(events(), "occupied", "true") >= 1,
                "the occupied=true motion edge");
        awaitTrue(() -> frameCount(0x0006, 0x01) >= 1,
                "the On frame reaching the scripted NCP",
                () -> newestReportedPosition(events(), "occupied", "true"));
        awaitTrue(() -> countCommandIssued(events(), "turn_on") >= 1,
                "command_issued(turn_on)");
        EventEnvelope issued = newestCommandIssued(events(), "turn_on");
        rig.reportOnOff(true);
        rig.deliverAndCycle();
        awaitTrue(() -> confirmedFor(events(), issued),
                "state_confirmed for the turn_on command",
                () -> newestReportedPosition(events(), "on", "true"));

        // The census — settled, printed in the frozen grammar, then scored.
        SettledCensus settled = awaitSettledCensus(core);
        System.out.println(renderTokens(settled.census(), 1));
        if (!settled.settled()) {
            throw timeoutDiagnostic(core, tempDir, lineCapture.list,
                    "the position census settling to missed=0 for every scored subscriber",
                    settled.firstScoredMiss());
        }
        for (SubscriberCensus census : settled.census()) {
            if (census.scored()) {
                assertThat(census.missed())
                        .as("bus.position_census: subscriber=%s missed", census.subscriberId())
                        .isZero();
            }
        }
        SubscriberCensus projection = settled.census().stream()
                .filter(census -> "state_projection".equals(census.subscriberId()))
                .findFirst().orElseThrow();
        assertThat(projection.atomic())
                .as("state_projection is reported atomic=true, never scored")
                .isTrue();
        assertThat(settled.census()).hasSize(manifest().size());
    }

    // ════════════════════════════════════════════════════════════════════════
    // The census — shared with BusSoakIT (package-private statics)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * One subscriber of the composition root's manifest: its id, its filter as
     * registered, and whether its checkpoint is written atomically with its view
     * (AMD-45 §2.2 — reported, never scored).
     *
     * @param subscriberId the bus subscriber id
     * @param filter       the registered {@link SubscriptionFilter}
     * @param atomic       {@code true} for the atomic-checkpoint projection
     */
    record ManifestEntry(String subscriberId, SubscriptionFilter filter, boolean atomic) {
    }

    /**
     * The census of one subscriber over one position set (TR-1 §1).
     *
     * @param subscriberId the bus subscriber id
     * @param atomic       whether the subscriber's checkpoint is atomic (unscored)
     * @param dlqDepth     the snapshot's DLQ depth ({@code > 0} ⇒ unscored)
     * @param pendingDepth the snapshot's hint-queue depth — wake hints offered to
     *                     the LIVE loop and not yet consumed (FIX-2b-i, re-read by
     *                     BUS-ORDER-1; reported, never scored)
     * @param checkpoint   the persisted checkpoint (the durable floor for restart)
     * @param deliveredMax the bus's in-memory cursor for the subscriber — the highest
     *                     position delivered or filtered past (BUS-ORDER-1); the
     *                     census is scored against it
     * @param matched      positions the filter accepts
     * @param delivered    matched positions at or below the cursor
     * @param missed       {@code matched − delivered}
     * @param firstMissed  the lowest missed position, or empty
     */
    record SubscriberCensus(String subscriberId, boolean atomic, int dlqDepth, int pendingDepth,
            long checkpoint, long deliveredMax, long matched, long delivered, long missed,
            OptionalLong firstMissed) {

        /** True when {@code missed} is an assertion, not a reading. */
        boolean scored() {
            return !atomic && dlqDepth == 0;
        }
    }

    /**
     * The census after the settle window.
     *
     * @param census  the last census read
     * @param settled {@code true} when every scored subscriber showed {@code missed=0}
     */
    record SettledCensus(List<SubscriberCensus> census, boolean settled) {

        /** The first missed position of the first scored subscriber with a miss, or empty. */
        OptionalLong firstScoredMiss() {
            return census.stream()
                    .filter(SubscriberCensus::scored)
                    .filter(subscriber -> subscriber.missed() > 0)
                    .map(SubscriberCensus::firstMissed)
                    .findFirst()
                    .orElse(OptionalLong.empty());
        }
    }

    /**
     * The six runtime subscribers the composition root registers, each with the
     * filter obtained the way the source exposes it: {@code state_projection}
     * ({@code SubscriptionFilter.all()}, {@code atomicCheckpoint = true},
     * {@code HomeSynapseCore:632–:636}) · {@code automation_engine}
     * ({@code SubscriptionFilter.all()}, {@code :735}) · {@code command_dispatch_service}
     * ({@code :752}) · {@code pending_command_ledger} ({@code :779}) ·
     * {@code integration_supervisor} ({@code :847}) · {@code registry_projection}
     * ({@code :605}; the type filter — the only subscriber a store-head await cannot reach).
     *
     * @return the manifest, in the instruction's order
     */
    static List<ManifestEntry> manifest() {
        return List.of(
                new ManifestEntry("state_projection", SubscriptionFilter.all(), true),
                new ManifestEntry("automation_engine", SubscriptionFilter.all(), false),
                new ManifestEntry(CommandDispatchAssembly.SUBSCRIBER_ID,
                        CommandDispatchAssembly.subscriptionFilter(), false),
                new ManifestEntry(PendingCommandLedgerAssembly.SUBSCRIBER_ID,
                        PendingCommandLedgerAssembly.subscriptionFilter(), false),
                new ManifestEntry(IntegrationSupervisorAssembly.SUBSCRIBER_ID,
                        IntegrationSupervisorAssembly.subscriptionFilter(), false),
                new ManifestEntry(RegistryProjectionSubscriber.SUBSCRIBER_ID,
                        RegistryProjectionSubscriber.subscriptionFilter(), false));
    }

    /**
     * Scores the manifest over one position set, one set of snapshots and the
     * bus's cursors.
     *
     * @param events    every envelope in the store (the position set — never a range)
     * @param snapshots the bus's {@code subscribers()} read AFTER the events
     * @param cursorOf  the bus's in-memory cursor per subscriber id
     *                  ({@code InProcessEventBus::lastDelivered}), read AFTER the
     *                  events — {@code DELIVERED(S,P) := P <= cursorOf(S)} (BUS-ORDER-1)
     * @return one census per manifest entry, in manifest order
     * @throws AssertionError if a manifest subscriber is not registered on the bus
     */
    static List<SubscriberCensus> census(List<EventEnvelope> events,
            List<SubscriberSnapshot> snapshots, ToLongFunction<String> cursorOf) {
        List<SubscriberCensus> out = new ArrayList<>(manifest().size());
        for (ManifestEntry entry : manifest()) {
            SubscriberSnapshot snapshot = snapshots.stream()
                    .filter(candidate -> entry.subscriberId().equals(candidate.subscriberId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "subscriber " + entry.subscriberId() + " is not registered on the bus"));
            long deliveredMax = cursorOf.applyAsLong(entry.subscriberId());
            long matched = 0L;
            long delivered = 0L;
            long firstMissed = Long.MAX_VALUE;
            for (EventEnvelope event : events) {
                if (!entry.filter().matches(event)) {
                    continue;
                }
                matched++;
                if (event.globalPosition() <= deliveredMax) {
                    delivered++;
                } else {
                    firstMissed = Math.min(firstMissed, event.globalPosition());
                }
            }
            long missed = matched - delivered;
            out.add(new SubscriberCensus(entry.subscriberId(), entry.atomic(),
                    snapshot.dlqDepth(), snapshot.pendingDepth(), snapshot.checkpoint(),
                    deliveredMax, matched, delivered, missed,
                    missed == 0 ? OptionalLong.empty() : OptionalLong.of(firstMissed)));
        }
        return List.copyOf(out);
    }

    /**
     * Re-reads the census until every scored subscriber shows {@code missed=0} or
     * the window closes (500 × 20 ms — the hero test's).
     *
     * @param core the booted core
     * @return the last census and whether it settled
     */
    static SettledCensus awaitSettledCensus(HomeSynapseCore core) {
        InProcessEventBus bus = concreteBus(core);
        List<SubscriberCensus> last = List.of();
        for (int poll = 0; poll < 500; poll++) {
            List<EventEnvelope> events = allEvents(core.eventStore());
            last = census(events, bus.subscribers(), bus::lastDelivered);
            if (last.stream().filter(SubscriberCensus::scored)
                    .allMatch(subscriber -> subscriber.missed() == 0)) {
                return new SettledCensus(last, true);
            }
            sleepBriefly();
        }
        return new SettledCensus(last, false);
    }

    /**
     * The frozen TR-1 §3 grammar — the five frozen keys first, then the readings
     * this instrument adds after them ({@code checkpoint}, {@code delivered_max}
     * (BUS-ORDER-1 — the cursor the census is scored against), {@code dlq},
     * {@code pending} (FIX-2b-i — un-consumed wake hints since BUS-ORDER-1), the
     * {@code atomic=true} / {@code dlq_unscored=true} flags). The total's
     * {@code missed} counts SCORED subscribers only; {@code unscored_missed}
     * carries the rest so nothing is hidden.
     *
     * @param census the census to print
     * @param runs   the hero loops the census spans
     * @return the token lines, {@code \n}-joined, no trailing newline
     */
    static String renderTokens(List<SubscriberCensus> census, int runs) {
        StringBuilder out = new StringBuilder(128 * (census.size() + 1));
        long scoredMissed = 0L;
        long unscoredMissed = 0L;
        for (SubscriberCensus subscriber : census) {
            out.append("bus.position_census: subscriber=").append(subscriber.subscriberId())
                    .append(" matched=").append(subscriber.matched())
                    .append(" delivered=").append(subscriber.delivered())
                    .append(" missed=").append(subscriber.missed())
                    .append(" first_missed=").append(subscriber.firstMissed().isPresent()
                            ? Long.toString(subscriber.firstMissed().getAsLong()) : "none")
                    .append(" checkpoint=").append(subscriber.checkpoint())
                    .append(" delivered_max=").append(subscriber.deliveredMax())
                    .append(" dlq=").append(subscriber.dlqDepth())
                    .append(" pending=").append(subscriber.pendingDepth());
            if (subscriber.atomic()) {
                out.append(" atomic=true");
            }
            if (subscriber.dlqDepth() > 0) {
                out.append(" dlq_unscored=true");
            }
            out.append('\n');
            if (subscriber.scored()) {
                scoredMissed += subscriber.missed();
            } else {
                unscoredMissed += subscriber.missed();
            }
        }
        out.append("bus.position_census_total: runs=").append(runs)
                .append(" subscribers=").append(census.size())
                .append(" missed=").append(scoredMissed)
                .append(" unscored_missed=").append(unscoredMissed);
        return out.toString();
    }

    /**
     * Every envelope in the store, paged (the position set is never a range).
     *
     * @param store the event store
     * @return the envelopes in {@code globalPosition} order
     */
    static List<EventEnvelope> allEvents(EventStore store) {
        List<EventEnvelope> all = new ArrayList<>();
        long after = 0L;
        while (true) {
            EventPage page = store.readFrom(after, 500);
            all.addAll(page.events());
            if (!page.hasMore() || page.events().isEmpty()) {
                return all;
            }
            after = page.nextPosition();
        }
    }

    /**
     * The (A) diagnostic for the two census/soak ITs: the store head, the awaited
     * position, every subscriber's mode/checkpoint/dlq/pending/behind — printed,
     * and the error to throw. An instrument never becomes the failure channel.
     * FIX-2b-i (B): the thread dump ({@link BusThreadDump#capture}, virtual
     * threads included when {@code jcmd} attaches) is appended after the
     * subscriber lines in stdout (the XML {@code <system-out>}); the thrown
     * message stays the reading (the XML {@code <failure message>}).
     * FIX-2b-ii (i): one more line follows the reading in BOTH — the
     * {@code automation.handoff_census:} of the newest triggered run
     * ({@link #handoffCensus}), computed from the captured log lines.
     * BUS-ORDER-1: the subscriber lines carry {@code cursor=} (the bus's
     * in-memory cursor, read through the concrete bus) and {@code hints=} (the
     * renamed {@code pending=}); between the reading and the hand-off census sit
     * {@code bus.head: position=<H> type=<eventType>} and up to three
     * {@code bus.tail: position=<P> type=<eventType>} lines — the head's event
     * type and the three positions below it, oldest first ({@link #headAndTail}).
     *
     * @param core     the booted core
     * @param dumpDir  the directory for the dump file (the test's temp dir)
     * @param captured the {@code com.homesynapse} lines captured since before boot
     * @param what     what was awaited
     * @param awaited  the position the await was gated on, when known
     * @return the error to throw
     */
    static AssertionError timeoutDiagnostic(HomeSynapseCore core, Path dumpDir,
            List<ILoggingEvent> captured, String what, OptionalLong awaited) {
        String reading;
        try {
            List<EventEnvelope> events = allEvents(core.eventStore());
            long storeHead = events.stream()
                    .mapToLong(EventEnvelope::globalPosition).max().orElse(0L);
            InProcessEventBus bus = concreteBus(core);
            reading = BusAwaitDiagnostic.render(what, storeHead, awaited,
                    bus.subscribers(), bus::lastDelivered)
                    + "\n" + headAndTail(events);
        } catch (RuntimeException gatherFailure) {
            reading = "timed out awaiting " + what
                    + " (bus.await_timeout unavailable: " + gatherFailure + ")";
        }
        String census;
        try {
            census = handoffCensus(allEvents(core.eventStore()), captured);
        } catch (RuntimeException gatherFailure) {
            census = "automation.handoff_census: unavailable: " + gatherFailure;
        }
        reading = reading + "\n" + census;
        System.out.println(reading + "\n" + BusThreadDump.capture(dumpDir));
        return new AssertionError(reading);
    }

    /**
     * BUS-ORDER-1: the composition root's bus as its concrete type — the census
     * scores the in-memory cursor ({@link InProcessEventBus#lastDelivered}), which
     * the {@code EventBus} interface does not expose (the {@code abandon()}
     * precedent: concrete-only, reached by a cast from test code).
     *
     * @param core the booted core
     * @return the bus as {@link InProcessEventBus}
     * @throws AssertionError if the core's bus is not an {@link InProcessEventBus}
     */
    static InProcessEventBus concreteBus(HomeSynapseCore core) {
        if (core.eventBus() instanceof InProcessEventBus bus) {
            return bus;
        }
        throw new AssertionError("the composition root's bus is not an InProcessEventBus: "
                + core.eventBus().getClass().getName());
    }

    /**
     * BUS-ORDER-1: the head's event type and the three positions below it, so a
     * red names WHAT sits at the head, not only where it is.
     *
     * @param events every envelope in the store, in position order
     * @return {@code bus.head: position=<H> type=<T>} then up to three
     *         {@code bus.tail: position=<P> type=<T>} lines (oldest first),
     *         {@code \n}-joined; {@code bus.head: position=0 type=none} on an empty store
     */
    static String headAndTail(List<EventEnvelope> events) {
        if (events.isEmpty()) {
            return "bus.head: position=0 type=none";
        }
        EventEnvelope head = events.get(events.size() - 1);
        StringBuilder out = new StringBuilder(160);
        out.append("bus.head: position=").append(head.globalPosition())
                .append(" type=").append(head.eventType());
        for (int i = Math.max(0, events.size() - 4); i < events.size() - 1; i++) {
            EventEnvelope tail = events.get(i);
            out.append("\nbus.tail: position=").append(tail.globalPosition())
                    .append(" type=").append(tail.eventType());
        }
        return out.toString();
    }

    /**
     * FIX-2b-ii (i): how far the newest triggered run got, read from the captured
     * {@code automation.*} lines keyed on that run's id (the newest
     * {@code automation_triggered} envelope's payload {@code runId} — the store's
     * word, not the log's). Each value is a COUNT of lines for that run:
     * {@code 1} is the healthy reading, {@code 0} names the step that never
     * happened, {@code 2} a double hand-off. {@code runId=none} with zeros when
     * nothing was triggered yet.
     *
     * @param events   every envelope in the store
     * @param captured the captured log events
     * @return {@code automation.handoff_census: runId=<ulid|none> handoff=<n> body=<n> step0=<n> died=<n>}
     */
    static String handoffCensus(List<EventEnvelope> events, List<ILoggingEvent> captured) {
        List<EventEnvelope> triggered = events.stream()
                .filter(event -> event.eventType().equals(EventTypes.AUTOMATION_TRIGGERED))
                .toList();
        if (triggered.isEmpty()) {
            return "automation.handoff_census: runId=none handoff=0 body=0 step0=0 died=0";
        }
        String runId = ((AutomationTriggeredEvent) triggered.get(triggered.size() - 1).payload())
                .runId().toString();
        List<String> lines = List.copyOf(captured).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        String keyed = "runId=" + runId + " ";
        long handoff = countStartingWith(lines, "automation.run_handoff: " + keyed);
        long body = countStartingWith(lines, "automation.run_body_entered: " + keyed);
        long step0 = lines.stream()
                .filter(line -> line.startsWith("automation.action_step_started: " + keyed))
                .filter(line -> line.contains(" index=0 "))
                .count();
        long died = countStartingWith(lines, "automation.run_thread_died: " + keyed);
        return "automation.handoff_census: runId=" + runId + " handoff=" + handoff
                + " body=" + body + " step0=" + step0 + " died=" + died;
    }

    private static long countStartingWith(List<String> lines, String prefix) {
        return lines.stream().filter(line -> line.startsWith(prefix)).count();
    }

    /**
     * Attaches a fresh, started {@link ListAppender} to the {@code com.homesynapse}
     * parent logger; the caller detaches it in {@code @AfterEach}.
     *
     * @return the appender, receiving every module's lines from now on
     */
    static ListAppender<ILoggingEvent> attachLineCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        homesynapseLogger().addAppender(appender);
        return appender;
    }

    /**
     * The {@code com.homesynapse} PARENT logger. {@code HomeSynapseCore.class}'s
     * own logger is the leaf {@code com.homesynapse.lifecycle.HomeSynapseCore}: an
     * appender there sees the bus's anomaly WARN (logged by that class) but never
     * an automation line — the parent sees both.
     *
     * @return the logback logger
     */
    static ch.qos.logback.classic.Logger homesynapseLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.homesynapse");
    }

    // ── store reads shared with BusSoakIT (pure functions over the log) ──────

    static long reportedCount(List<EventEnvelope> events, String attributeKey, String value) {
        return events.stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_REPORTED))
                .filter(event -> reports(event, attributeKey, value))
                .count();
    }

    static long newestReportedPosition(List<EventEnvelope> events, String attributeKey,
            String value) {
        return events.stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_REPORTED))
                .filter(event -> reports(event, attributeKey, value))
                .mapToLong(EventEnvelope::globalPosition)
                .max()
                .orElse(NO_AWAITED_POSITION);
    }

    private static boolean reports(EventEnvelope event, String attributeKey, String value) {
        StateReportedEvent reported = (StateReportedEvent) event.payload();
        return attributeKey.equals(reported.attributeKey()) && value.equals(reported.value());
    }

    static long countCommandIssued(List<EventEnvelope> events, String commandType) {
        return events.stream()
                .filter(event -> event.eventType().equals(EventTypes.COMMAND_ISSUED))
                .filter(event -> commandType(event).equals(commandType))
                .count();
    }

    static EventEnvelope newestCommandIssued(List<EventEnvelope> events, String commandType) {
        return events.stream()
                .filter(event -> event.eventType().equals(EventTypes.COMMAND_ISSUED))
                .filter(event -> commandType(event).equals(commandType))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "no command_issued(" + commandType + ") in the log"));
    }

    static boolean confirmedFor(List<EventEnvelope> events, EventEnvelope issued) {
        return events.stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_CONFIRMED))
                .anyMatch(event -> ((StateConfirmedEvent) event.payload()).commandEventId()
                        .equals(issued.eventId()));
    }

    private static String commandType(EventEnvelope envelope) {
        return envelope.payload() instanceof CommandIssuedEvent issued
                ? issued.commandType() : "";
    }

    static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the bus census", ex);
        }
    }

    /**
     * The one automation the census loop needs: the hero motion → turn_on rule
     * ({@code HeroLoopHardwareFreeIT}'s first automation, verbatim).
     *
     * @return the YAML
     */
    static String heroMotionConfigYaml() {
        return """
                automation:
                  automations:
                    - name: "hero motion lights"
                      slug: "hero-motion"
                      triggers:
                        - type: state_change
                          label: "motion"
                          attribute: "occupied"
                          to: "true"
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: turn_on
                """;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness — the HeroLoopHardwareFreeIT boot shape, copied
    // ════════════════════════════════════════════════════════════════════════

    private void bootAndAdopt(Path tempDir) throws Exception {
        this.tempDir = tempDir;
        clock = TestClock.createDefault();
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), heroMotionConfigYaml());
        rig = new ZigbeeHardwareFreeRig(clock, () -> core.deviceRegistry(),
                () -> core.registryProjection(),
                tempDir.resolve("zigbee"));
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                configDir,
                HomeSynapseConfig.testing(),
                clock,
                TEST_HOME_ID,
                null,
                List.of(rig.factory()));
        core.start();
        core.registerIntegrationSchema(ZigbeeIntegrationFactory.INTEGRATION_TYPE,
                ZigbeeIntegrationFactory.configSchemaJson());
        awaitRuntimeSubscribersLive();
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");

        rig.announce(ZigbeeHardwareFreeRig.SNZB_IEEE);
        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        snzbEntity = rig.adopt(ZigbeeHardwareFreeRig.SNZB_IEEE)
                .get(ZigbeeHardwareFreeRig.SNZB_ENDPOINT);
        hueEntity = rig.adopt(ZigbeeHardwareFreeRig.HUE_IEEE)
                .get(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        awaitRegistryProjectionCaughtUp();
        label(snzbEntity, "motion");
        label(hueEntity, "hero-light");
    }

    /** Re-registers an adopted entity with a selector label (the trigger/action join). */
    private void label(EntityId entityId, String labelValue) {
        Entity entity = core.entityRegistry().getEntity(entityId);
        core.entityRegistry().updateEntity(new Entity(entity.entityId(),
                entity.entitySlug(), entity.entityType(), entity.displayName(),
                entity.deviceId(), entity.endpointIndex(), entity.areaId(),
                entity.enabled(), List.of(labelValue), entity.capabilities(),
                entity.entityRole(), entity.createdAt()));
    }

    private void awaitRuntimeSubscribersLive() {
        for (int poll = 0; poll < 250; poll++) {
            if (subscriberMode("automation_engine") == SubscriberMode.LIVE
                    && subscriberMode("command_dispatch_service") == SubscriberMode.LIVE
                    && subscriberMode("pending_command_ledger") == SubscriberMode.LIVE
                    && subscriberMode("integration_supervisor") == SubscriberMode.LIVE
                    && subscriberMode("state_projection") == SubscriberMode.LIVE) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("runtime subscribers did not reach LIVE within ~5s");
    }

    private SubscriberMode subscriberMode(String subscriberId) {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> subscriberId.equals(snapshot.subscriberId()))
                .map(SubscriberSnapshot::mode)
                .findFirst()
                .orElse(SubscriberMode.COLD);
    }

    /** The M9.5-DURc registry-projection checkpoint barrier (see the hero IT's javadoc). */
    private void awaitRegistryProjectionCaughtUp() {
        long lastRegistrationFact = events().stream()
                .filter(event -> event.eventType().equals(EventTypes.DEVICE_REGISTERED)
                        || event.eventType().equals(EventTypes.ENTITY_REGISTERED))
                .mapToLong(EventEnvelope::globalPosition)
                .max()
                .orElseThrow(() -> new AssertionError(
                        "no registration facts in the log after adopt()"));
        awaitTrue(() -> registryProjectionCheckpoint() >= lastRegistrationFact,
                "the registry projection consuming the adoption events",
                () -> lastRegistrationFact);
    }

    private long registryProjectionCheckpoint() {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> RegistryProjectionSubscriber.SUBSCRIBER_ID
                        .equals(snapshot.subscriberId()))
                .mapToLong(SubscriberSnapshot::checkpoint)
                .findFirst()
                .orElse(0L);
    }

    private List<EventEnvelope> events() {
        return allEvents(core.eventStore());
    }

    private long frameCount(int clusterId, int commandId) {
        return rig.sentZclFrames().stream()
                .filter(frame -> frame.clusterId() == clusterId
                        && frame.commandId() == commandId)
                .count();
    }

    private void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, what, () -> NO_AWAITED_POSITION);
    }

    private void awaitTrue(BooleanSupplier condition, String what,
            LongSupplier awaitedPosition) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        long position = awaitedPosition.getAsLong();
        throw timeoutDiagnostic(core, tempDir, lineCapture.list, what,
                position < 0 ? OptionalLong.empty() : OptionalLong.of(position));
    }
}
