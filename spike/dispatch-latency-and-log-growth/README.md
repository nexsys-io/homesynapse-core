# Spike: dispatch-latency-and-log-growth  — THROWAWAY / DISPOSABLE

> **THROWAWAY SPIKE CODE.** Not production. No coding-standard expectations.
> Lives outside the production source tree on purpose. **Slated for `git rm`**
> once the §1 D1 validation + D4 sizing numbers are folded into the spine.
> Per `spike/README.md`: spike findings are documented in the docs/governance
> repos, not here. The authoritative write-up is:
> `nexsys-hivemind/context/assessments/2026-06-26_pi-dispatch-latency-and-log-growth_spike.md`

## What this answers (the two §1 numbers)

Commissioned by the v6 hub's parallel fan-out v2 (Session V2-A, 2026-06-26) to
produce the two Pi-class numbers the ratified §1 architecture wants:

1. **Dispatch-seam latency (validates D1).** The added latency of the
   *logical-event-driven, physically co-located* command hop — the executor
   emits a `command_issued` event into an in-process synchronous bus and a
   co-located dispatch subscriber consumes it (same JVM, same thread) — vs a
   direct in-process `dispatch(cmd)` call. D1 is **RATIFIED**; this *validates*
   the co-location mitigation is negligible, it does not decide it.

2. **Log growth + replay (sizes D4).** Event-log append throughput, on-disk
   bytes/event (SQLite WAL, the real engine), and projection-rebuild (replay)
   time as the log grows (N = 1e4, 1e5, 1e6) — to size D4's snapshot cadence +
   retention windows (the Home-Assistant Recorder-DB scaling-wall the prior-art
   study flagged).

Both honor **D2** (pure-function replay): B1 asserts a REPLAY pass produces ZERO
dispatch side-effects (subscriber acts only in LIVE); B2's replay is a pure fold
into an in-memory entity-state map with no external side-effects.

## Files

```
src/com/homesynapse/spike/dispatch/
  DispatchLatencyBenchmark.java   # B1: seam latency (direct vs co-located event-driven) + D2 assert
  LogGrowthBenchmark.java         # B2: SQLite-WAL append/bytes-per-event + replay-rebuild time
run.sh                            # one-shot: detect JDK, fetch sqlite-jdbc, compile, run BOTH
lib/                              # sqlite-jdbc + slf4j jars (fetched from Maven Central by run.sh)
README.md                         # this file
```

There is intentionally **no Gradle wiring** and **no `module-info.java`** — the
spike is deliberately self-contained (plain `javac`/`java` + a couple of jars)
so it does not touch the production multi-module build or the shared version
catalog, and so Nick can drop it on the Pi and run it unchanged.

## How to run (dev box OR Raspberry Pi)

```bash
./run.sh
```

`run.sh` will: find a JDK with `javac` (or fetch a portable Temurin 21 — the
HomeSynapse target — to `/tmp/jdk21`); fetch `sqlite-jdbc` (3.45.3.0, which
bundles native libs incl. aarch64/arm for the Pi) + `slf4j` from Maven Central
if `lib/` is empty; compile to a local out dir; and run both benchmarks,
printing host facts (CPU, storage) first.

**Storage caveat baked into the script:** the DB benchmark writes its scratch
DBs to a **local real filesystem** (`/tmp/hs-spike-db` by default), NEVER into
the repo tree. On this dev sandbox the repo is a **FUSE mount** and SQLite WAL
fails on it (`SQLITE_IOERR_DELETE` — WAL needs real mmap/unlink/shared-memory).
The Pi's SD/SSD ext4 is a real FS, so this is a dev-sandbox artifact, not a Pi
concern — but the rule (run the DB bench on a real FS) stands.

### Useful env overrides
```bash
B1_WARMUP, B1_TRIALS, B1_OPS         # B1 sizing (defaults 2e6 / 20 / 2e7)
B2_ENTITIES, B2_BATCH, B2_NS         # B2 sizing (defaults 200 / 500 / "10000 100000 1000000")
SPIKE_DBDIR, SPIKE_OUT, SQLITE_VER   # scratch DB dir / class out dir / driver version
```
On a disk-tight Pi, drop the 1e6 point: `B2_NS="10000 100000" ./run.sh`.

Machine-readable result lines (for diffing dev vs Pi): grep `RESULT_B1`,
`RESULT_B2`, `RESULT_B2C`.

## Dev-machine baseline (for comparison; see the report for full tables + caveats)

- JVM: Temurin OpenJDK 21.0.11 (LTS). CPU: AMD Ryzen 9 7900X (2 vCPU visible,
  ~4.69 GHz), x86_64 under Hyper-V. DB bench on ext4 (`/dev/sda1`).
- **B1:** direct ≈ 0.63 ns/op, event-driven ≈ 2.54 ns/op, **seam delta ≈ 1.9 ns/op**.
  D2 PASS (0 side-effects over 1e6 replayed events).
- **B2:** **~488 bytes/event** (constant across 1e4–1e6; includes the
  `idx_events_entity` index). Append ~60k–98k ev/s at batch=500. Replay
  43.9 ms (1e4) → 107.7 ms (1e5) → 625 ms (1e6). Per-event fsync (batch=1)
  is ~8–9x slower than batch=500.

**The dev box is an OPTIMISTIC upper bound vs the Pi** (ARM ~3–5x slower CPU;
SD-card random-write + fsync far slower). Append/fsync numbers degrade most on
Pi; replay (CPU+scan bound) transfers better — scale by the CPU ratio. Get real
Pi numbers by running `./run.sh` on the Pi unchanged.
