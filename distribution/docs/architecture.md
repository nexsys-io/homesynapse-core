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

## The artifact channel {#artifacts}

`install-smoke` (`../ci/install-smoke.yml`, wired at `.github/workflows/install-smoke.yml`) runs as
a **two-architecture matrix** on every push to `main`/`develop` that touches `distribution/**`,
`app/**`, `lifecycle/**`, `api/**`, `gradle/**`, `build-logic/**`, or the workflow file itself
(and on every PR to `main` touching the first four): `ubuntu-latest` (amd64) and
`ubuntu-24.04-arm` (arm64 — GitHub-hosted, free for public repositories). **Both legs run the
same scripts byte-for-byte** — `build-image.sh` → `build-deb.sh` → `run-smoke.sh` (checks 1–9) →
`update-smoke.sh`; only the runner differs, and the architecture comes from `hs_deb_arch`
(`dpkg --print-architecture` on that runner), never from a parameter — a per-arch branch in a
script is a defect. `fail-fast: false`, so one leg's red never hides the other's verdict. This is
escalation E1's option (c) — a native arm64 runner — taken.

Two asserts ride each leg after "Assemble .deb", in-workflow and in-script:

- **arch-truth** — `dpkg-deb --field <deb> Architecture` must equal the leg's arch. An amd64 image
  packaged under an arm64 label would pass every later check on the same runner; the control field
  is the cheapest honest discriminator, the boot in `run-smoke.sh` the second.
- **version-grammar** — `hs_version`'s output and the `.deb`'s `Version` field must match
  `^[0-9]+\.[0-9]+\.[0-9]+`, the same regex `build-image.sh` asserts at its capture site;
  `../smoke/version-grammar-test.sh` (run in the Static-lint step) pins the function's fixtures
  and the regex literal in its three carriers.

Each leg uploads one artifact, retained **7 days**:

| Artifact | Contents |
|---|---|
| `distribution-artifacts-amd64` | `homesynapse_<ver>_amd64.deb` + `homesynapse_<ver>_amd64.tar.gz` |
| `distribution-artifacts-arm64` | `homesynapse_<ver>_arm64.deb` + `homesynapse_<ver>_arm64.tar.gz` |

The upload step runs `if: always()`, so a red leg still uploads what it built: **the verdict is the
job status, never the artifact's existence** — read the job before trusting the file. The arm64
artifact has, by construction, already passed checks 1–9 and update-smoke on a clean arm64 machine;
it is the artifact source for the install-rehearsal cadence and the packaged integration runs.
The channel is a build fact proven on a hosted runner, not on a Pi — it lifts no claim fence.

### Fetching an artifact for a Pi install

`gh` is absent on the Pi. Download on the desktop, copy to the Pi, hash on **every** hop:

```bash
# desktop — the gh CLI path (the run id comes from `gh run list`)
gh run list --repo nexsys-io/homesynapse-core --workflow install-smoke --limit 5
gh run download <run-id> --repo nexsys-io/homesynapse-core -n distribution-artifacts-arm64 -D ./arm64
sha256sum ./arm64/homesynapse_*_arm64.deb
```

Browser path: the run page (Actions → install-smoke → the run) → **Artifacts** →
`distribution-artifacts-arm64` downloads a zip; unzip it and `sha256sum` the `.deb` the same way.

```bash
# desktop → Pi, then install on the Pi
scp ./arm64/homesynapse_<ver>_arm64.deb pi:
ssh pi 'sha256sum homesynapse_<ver>_arm64.deb'           # must equal the desktop hash
ssh pi 'sudo apt install ./homesynapse_<ver>_arm64.deb'   # --allow-downgrades: see below
```

The tarball in the same artifact is the dpkg-free path (`sudo ../install/install.sh <tarball>`) and the
input to `../update/update.sh`; both verify `MANIFEST.sha256` before trusting an image.

### The version string (post-R-V)

`hs_version` (`../common.sh`) resolves: explicit `HS_VERSION` → **git** (`git describe --tags
--always --dirty` — the commit decides, whatever the cwd) → a `VERSION` file beside the script or
one level up (the git-less carrier: a tarball export, a container without git; the tracked
`distribution/VERSION` reads `0.1.0-skeleton`) → `0.1.0-skeleton`. Under the build scripts'
`bash -c '. common.sh; hs_version'` idiom `$0` is `bash`, so `build-image.sh` and `build-deb.sh`
pass `HS_DIST_DIR="${DIST}"` to pin the lookup dir; the install-smoke echo step passes
`HS_DIST_DIR=distribution` the same way — one instrument.

A describe output with a dot is tag-derived and passes through (`1.2.3`, `1.2.3-5-gabc1234`,
`1.2.3-dirty`); a bare commit id never has one and is wrapped as
**`0.1.0+git<YYYYMMDD.HHMMSS>.g<id>`** — the committer date in UTC (`git log -1 --format=%cd
--date=format-local:%Y%m%d.%H%M%S` under `TZ=UTC`): `0.1.0+git20260822.143100.g7c9e4fa`,
`0.1.0+git20260822.143100.g7c9e4fa-dirty`. A missing or malformed date yields the empty string,
which `build-image.sh` refuses (fail-closed — never a lawful-looking `0.1.0+g<id>`, which would
sort below every `+git` build). Tags must be digit-leading (`1.2.3`, never `v1.2.3`):
`build-image.sh` dies on any version of record that is not `^[0-9]+\.[0-9]+\.[0-9]+`, and the
echo step additionally refuses `0.1.0-skeleton` — the fallback passes the grammar but is never
lawful in CI (`actions/checkout` leaves `.git`).

**Why this shape orders.** dpkg sorts `+git…` above `+g…` because `g` is a proper prefix of
`git`, so every R-V build sorts above every legacy `0.1.0+g<id>` artifact; two R-V builds order by
committer time, monotone along `main` (a rebase re-stamps the committer date — the right clock
for ordering); the scheme is depth-free (`rev-list --count` is a constant 1 on a shallow CI
checkout; the commit carries its own date) and reproducible (commit date, never build date).
`../smoke/version-grammar-test.sh` asserts the ordering rows at `dpkg --compare-versions` itself
when the host has dpkg. The previous arm (`0.1.0+g<id>`, post-F-V1) did not order between builds
— `g7c9e4fa` and `gd26777c` compare as strings — so "`--allow-downgrades` exactly once" would
have broken on the second install (R-7 audit §2 H-2). Before F-V1 a digit-leading id shipped
bare (`7c9e4fa`, the 2026-08-22 Block-0 build), and dpkg orders `7c9e4fa` above every `0.x.y`.

**One-time cost, disclosed.** A card carrying a bare-id version (`dpkg-query -W -f '${Version}\n'
homesynapse` prints `7c9e4fa`) needs `sudo apt install --allow-downgrades ./homesynapse_<ver>_arm64.deb`
exactly once to move onto the scheme (`dpkg -i` proceeds with a downgrade warning); no later
install needs the flag. Tags begin at the first release, with a tagging rule written then — a
tag at HEAD today would describe as bare `0.1.0`, which dpkg sorts **below** every `0.1.0+…`
artifact.
