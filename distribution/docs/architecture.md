# Distribution architecture — the "why"

Companion to the top-level `README.md` (the "what"/"how") and `boot-contract-map.md` (the
source receipts). This file records the design rationale behind the two decisions most likely
to be questioned.

## Index
- `../README.md` — workspace overview, layout, one-command flow.
- `boot-contract-map.md` — every packaging choice mapped to source / Doc 12.
- `pairing-wizard-seam.md` — the post-M9 ramp seam (design only).
- `escalations.md` — Core/spine/cross-lane decisions (options + recommendation).

## Why a jlinked JRE + classpath, not a full modular image

The first-party modules are all explicit JPMS modules, but the third-party dependency set
(Javalin/Jetty, Jackson, sqlite-jdbc, logback, snakeyaml, …) mixes explicit and **automatic**
modules. `jlink` refuses to *link* automatic modules, so a single `jlink` of the whole app
would fail (or demand per-jar synthetic module-info / the badass-jlink merge).

The skeleton instead **jlinks only the JDK platform modules** into a minimal runtime, then runs
the app + all dependency jars on the **classpath** inside that runtime. This is the standard,
bulletproof pattern for shipping a self-contained Java service:

- **Offline & self-contained** — the image carries its own JRE and every jar; install fetches nothing.
- **No module-path landmines** — no automatic-module link errors, no split-package surprises.
- **Reflection-friendly** — Jackson et al. reflect freely (everything is on the classpath).
- **Small** — `--strip-debug --no-man-pages --compress` keep the runtime lean for the Pi.

Folding the link step into Gradle via badass-jlink (which *does* handle automatic modules) is
**escalation E5** — it needs a `build-logic/` edit, outside this lane. The script approach is
the in-lane skeleton; it drives `./gradlew installDist` for the compile+resolve, so Gradle
still owns the build.

## Why the update is non-destructive (and how the smoke proves it) {#update}

The event store is **append-only and event-sourced**; schema evolves through an **additive**
migration framework (`MigrationRunner`, `hs_schema_version`, `V001…V005`). The app runs those
migrations forward on start. Therefore the updater's only job is to swap the read-only image:

```
stop -> snapshot /opt/homesynapse -> swap in new image -> start -> readiness-gate
        (/var/lib/homesynapse is NEVER touched)        [auto-rollback on failure]
```

`update-smoke.sh` validates the **mechanism** by deriving a v2 image (same bytes, bumped
`VERSION`) and asserting, across the swap: `COUNT(*) FROM events` is non-decreasing, `home_id`
is unchanged (identity continuity), and `PRAGMA integrity_check = ok`. Real **cross-schema**
migration is validated when genuine new versions exist — the populated-store update test then
runs against an actual `V00N` bump; the harness already reads the live count, so that test is a
drop-in once a second real version lands.

## Reproducibility

JDK 21 is pinned (Corretto in CI; `libs.versions.toml`). The image build uses deterministic
`jlink` flags, a sorted `MANIFEST.sha256`, and a sorted/fixed-owner/fixed-mtime tarball, so the
same inputs produce the same bytes. `install.sh` and `update.sh` verify `MANIFEST.sha256`
before trusting an image.
