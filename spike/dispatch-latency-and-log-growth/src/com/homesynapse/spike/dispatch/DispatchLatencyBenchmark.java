/*
 * HomeSynapse Core — THROWAWAY SPIKE (disposable; slated for `git rm`).
 * Spike: dispatch-latency-and-log-growth  (validates §1 D1, asserts D2).
 *
 * NOT production code. No coding-standard expectations. Lives outside the
 * production source tree on purpose. Its only job is to ANSWER the question:
 *
 *   What latency does the logical-event-driven, physically co-located command
 *   dispatch (executor emits `command_issued` -> in-process synchronous bus ->
 *   a co-located dispatch subscriber consumes it, same JVM, same thread) ADD
 *   over a direct in-process dispatch(cmd) call?
 *
 * D1 (RATIFIED) says co-location makes that hop negligible. This VALIDATES it
 * (it does not decide it). It also ASSERTS D2: a subscriber acts only in LIVE
 * mode, so a REPLAY pass must produce ZERO dispatch side-effects.
 *
 * Design (per the dispatch brief, Benchmark 1):
 *   Path A (direct):    executor -> dispatch(cmd) -> no-op sink (accumulates a
 *                       field, read at the end so the JIT cannot DCE it).
 *   Path B (event-driven, co-located): executor -> bus.emit(command_issued) ->
 *                       synchronous in-thread delivery to a subscriber list ->
 *                       the dispatch subscriber consumes ONLY when mode==LIVE
 *                       and calls the SAME no-op sink.
 *
 * The delta (B - A) is the added cost of the event-driven hop. Real device
 * dispatch I/O is microseconds-to-milliseconds; we express the seam as an
 * absolute ns figure AND as a fraction of a realistic device-dispatch cost.
 */
package com.homesynapse.spike.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class DispatchLatencyBenchmark {

    // ---- Minimal domain stand-ins (NOT the real types) ---------------------

    /** Stand-in for a command to dispatch. Kept tiny; identity is what matters. */
    static final class Command {
        final long targetId;
        final int  action;
        Command(long targetId, int action) { this.targetId = targetId; this.action = action; }
    }

    /** Stand-in for the `command_issued` event carrying the command. */
    static final class CommandIssued {
        final Command command;
        CommandIssued(Command command) { this.command = command; }
    }

    /** The bus FSM mode. A subscriber acts only in LIVE (the D2 mechanism). */
    enum Mode { REPLAY, LIVE }

    /**
     * Minimal in-process synchronous event bus: a subscriber list + a mode flag,
     * synchronous in-thread delivery. Deliberately the *shape* of the real bus's
     * REPLAY->LIVE FSM, reduced to the part that costs latency.
     */
    static final class MiniBus {
        private final List<Consumer<CommandIssued>> subscribers = new ArrayList<>();
        private Mode mode = Mode.LIVE;

        void setMode(Mode m) { this.mode = m; }
        Mode mode() { return mode; }
        void subscribe(Consumer<CommandIssued> s) { subscribers.add(s); }

        /** Synchronous, in-thread fan-out — the co-located hop we are measuring. */
        void emit(CommandIssued event) {
            final List<Consumer<CommandIssued>> subs = subscribers;
            for (int i = 0, n = subs.size(); i < n; i++) {
                subs.get(i).accept(event);
            }
        }
    }

    /**
     * The no-op "device" sink. Both paths funnel here so they do identical work
     * downstream of the seam. Accumulates into a field read at the end (defeats
     * dead-code elimination). A SEPARATE counter is the D2 side-effect probe.
     */
    static final class NoopSink {
        long acc;
        long dispatchCount;
        void dispatch(Command c) {
            acc += (c.targetId * 1_000003L) ^ (c.action + 0x9E3779B9L);
            dispatchCount++;
        }
    }

    /**
     * The dispatch subscriber: a real bus subscriber on `command_issued`.
     * Embodies D1 ("dispatch service is a subscriber") and D2 ("acts only in
     * LIVE"). On REPLAY it returns immediately (zero side-effects).
     */
    static final class DispatchSubscriber implements Consumer<CommandIssued> {
        private final MiniBus bus;
        private final NoopSink sink;
        DispatchSubscriber(MiniBus bus, NoopSink sink) { this.bus = bus; this.sink = sink; }
        @Override public void accept(CommandIssued event) {
            if (bus.mode() != Mode.LIVE) {
                return; // D2: never dispatch on replay.
            }
            sink.dispatch(event.command);
        }
    }

    // ---- Stats helpers -----------------------------------------------------

    static double median(double[] xs) {
        double[] c = xs.clone();
        Arrays.sort(c);
        int n = c.length;
        return (n % 2 == 1) ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    static double percentile(double[] xs, double p) {
        double[] c = xs.clone();
        Arrays.sort(c);
        int idx = (int) Math.ceil(p / 100.0 * c.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= c.length) idx = c.length - 1;
        return c[idx];
    }

    // ---- The two measured paths --------------------------------------------

    static double timePathA_directNsPerOp(NoopSink sink, Command[] cmds, int opsPerTrial) {
        long start = System.nanoTime();
        for (int i = 0; i < opsPerTrial; i++) {
            sink.dispatch(cmds[i & (cmds.length - 1)]);
        }
        long elapsed = System.nanoTime() - start;
        return (double) elapsed / opsPerTrial;
    }

    static double timePathB_eventDrivenNsPerOp(MiniBus bus, Command[] cmds, int opsPerTrial) {
        long start = System.nanoTime();
        for (int i = 0; i < opsPerTrial; i++) {
            Command c = cmds[i & (cmds.length - 1)];
            bus.emit(new CommandIssued(c));   // emit `command_issued`
        }
        long elapsed = System.nanoTime() - start;
        return (double) elapsed / opsPerTrial;
    }

    // ---- Main --------------------------------------------------------------

    public static void main(String[] args) {
        int warmupOps   = arg(args, 0, 2_000_000);
        int trials      = arg(args, 1, 15);
        int opsPerTrial = arg(args, 2, 5_000_000);

        final int RING = 1024;
        Command[] cmds = new Command[RING];
        for (int i = 0; i < RING; i++) cmds[i] = new Command(1000 + i, i & 3);

        System.out.println("=== Benchmark 1: dispatch-latency seam overhead ===");
        System.out.println("JVM: " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version"));
        System.out.println("warmupOps=" + warmupOps + " trials=" + trials
                + " opsPerTrial=" + opsPerTrial);

        NoopSink sinkA = new NoopSink();
        NoopSink sinkB = new NoopSink();
        MiniBus  bus   = new MiniBus();
        bus.subscribe(new DispatchSubscriber(bus, sinkB));
        bus.setMode(Mode.LIVE);

        // Warmup (JIT): run BOTH paths so both are compiled.
        for (int i = 0; i < warmupOps; i++) {
            sinkA.dispatch(cmds[i & (RING - 1)]);
            bus.emit(new CommandIssued(cmds[i & (RING - 1)]));
        }
        System.out.println("warmup done. (sinkA.acc=" + sinkA.acc
                + ", sinkB.acc=" + sinkB.acc + ")  [printed so warmup isn't DCE'd]");

        double[] aNs = new double[trials];
        double[] bNs = new double[trials];
        for (int t = 0; t < trials; t++) {
            aNs[t] = timePathA_directNsPerOp(sinkA, cmds, opsPerTrial);
            bNs[t] = timePathB_eventDrivenNsPerOp(bus, cmds, opsPerTrial);
        }

        double aMed = median(aNs), aP99 = percentile(aNs, 99);
        double bMed = median(bNs), bP99 = percentile(bNs, 99);
        double deltaMed = bMed - aMed;

        System.out.println();
        System.out.println("--- RESULTS (ns/op; fractional, so the sub-ns seam is visible) ---");
        System.out.printf("Path A (direct call)        : median=%6.3f ns/op   p99=%6.3f ns/op%n", aMed, aP99);
        System.out.printf("Path B (event-driven hop)   : median=%6.3f ns/op   p99=%6.3f ns/op%n", bMed, bP99);
        System.out.printf("DELTA (B - A) = seam cost    : median=%6.3f ns/op%n", deltaMed);
        System.out.println("per-trial A ns/op: " + Arrays.toString(aNs));
        System.out.println("per-trial B ns/op: " + Arrays.toString(bNs));

        System.out.println();
        System.out.println("--- Seam cost vs realistic device-dispatch cost ---");
        double[] deviceCostsUs = { 1.0, 10.0, 100.0, 1000.0 };
        for (double us : deviceCostsUs) {
            double ns = us * 1000.0;
            double frac = (deltaMed / ns) * 100.0;
            System.out.printf("  device dispatch ~%-7s : seam is %.6f%% of dispatch cost%n",
                    fmtUs(us), frac);
        }

        // D2 ASSERTION: REPLAY must produce ZERO dispatch side-effects.
        System.out.println();
        System.out.println("--- D2 replay-safety assertion ---");
        long beforeReplay = sinkB.dispatchCount;
        bus.setMode(Mode.REPLAY);
        int replayEvents = 1_000_000;
        for (int i = 0; i < replayEvents; i++) {
            bus.emit(new CommandIssued(cmds[i & (RING - 1)]));
        }
        long replayDispatches = sinkB.dispatchCount - beforeReplay;
        boolean d2Pass = (replayDispatches == 0);
        System.out.printf("Replayed %d `command_issued` events in REPLAY mode.%n", replayEvents);
        System.out.printf("Dispatch side-effects during replay: %d  => D2 %s%n",
                replayDispatches, d2Pass ? "PASS (zero side-effects on replay)"
                                         : "FAIL (replay caused dispatch!)");

        bus.setMode(Mode.LIVE);
        long beforeLive = sinkB.dispatchCount;
        bus.emit(new CommandIssued(cmds[0]));
        long liveDelta = sinkB.dispatchCount - beforeLive;
        System.out.printf("Post-replay LIVE emit dispatched %d (expected 1) => %s%n",
                liveDelta, (liveDelta == 1 ? "OK" : "UNEXPECTED"));

        System.out.println();
        System.out.println("checksum sinkA.acc=" + sinkA.acc
                + " sinkB.acc=" + sinkB.acc
                + " sinkB.dispatchCount=" + sinkB.dispatchCount);

        System.out.printf("RESULT_B1 directMedNs=%.3f eventMedNs=%.3f deltaMedNs=%.3f aP99=%.3f bP99=%.3f d2Pass=%b%n",
                aMed, bMed, deltaMed, aP99, bP99, d2Pass);

        if (!d2Pass) {
            System.exit(2);
        }
    }

    private static int arg(String[] a, int i, int def) {
        return (a != null && a.length > i) ? Integer.parseInt(a[i]) : def;
    }

    private static String fmtUs(double us) {
        return (us >= 1000.0) ? ((long) (us / 1000.0) + "ms") : ((long) us + "us");
    }
}
