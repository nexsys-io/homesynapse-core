# Escalations to the hub (via Nick)

Per the lane's write-isolation, anything needing a Core change, a spine-owned file, or a
cross-lane decision is **surfaced here, not made**. Each item is options + a recommendation;
the hub adjudicates. Ordered by how much they block the launch path.

---

### E1 — Target architecture: arm64/Pi cross-build vs on-device build
**Context.** The image bundles a jlinked JRE, so artifacts are arch-specific. The demo target
is a Raspberry Pi (arm64); CI runs on amd64.
**Options.** (a) Build arm64 on the Pi itself (simple, slow, needs a Pi in the loop). (b)
Cross-build arm64 on amd64 CI using an arm64 JDK 21 + `qemu-user-static`/`binfmt` for the
smoke (fast, fully in CI, some setup). (c) Native arm64 CI runner (cleanest, depends on runner
availability/cost).
**Recommendation.** **(b)** now — produce both `amd64` (CI smoke) and `arm64` (release) images
in CI via a matrix; run the install-smoke on amd64 every commit and the arm64 smoke under
emulation on a schedule. Move to **(c)** if/when an arm64 runner is approved. *Decision needed
before the first release image; not blocking the skeleton.*

### E2 — Make bind host / port operator-configurable (Core)
**Context.** `HomeSynapseConfig.HOME_DEFAULT` hard-codes loopback `127.0.0.1:7070`; the current
artifact reads neither env nor config for these (YAML loading is deferred). A headless Pi's
dashboard is therefore unreachable without an SSH tunnel — and the **post-M9 wizard needs the
dashboard reachable**.
**Options.** (a) Read `HOMESYNAPSE_BIND_HOST`/`HOMESYNAPSE_HTTP_PORT` env in the composition
root (small, the env drop-in is already wired to feed it). (b) Full YAML config wiring (larger;
Doc 06 territory). (c) Leave loopback-only; document the `ssh -L 7070:127.0.0.1:7070` tunnel.
**Recommendation.** **(a)** as the authenticated LAN opt-in (mirrors AB-1: never all-interfaces
by default; bind is an explicit, documented opt-in). The env keys are already reserved in
`homesynapse.env.example`. *Prioritise ahead of the M9 wizard build.*

### E3 — Unauthenticated loopback `/health` (or `/livez` + `/readyz`) endpoint (Core)
**Status: CLOSED at R-9 (2026-08-22).** Option (a) landed: `GET`/`HEAD /health` — exempted from the auth filter for LOOPBACK callers only (R-H1), 200 ⇔ the state projection is LIVE, 503 otherwise, body `{"status":"<mode>"}` — is the unit's `ExecStartPost` path and every install/update probe's; the helper no longer touches the pairing artifact. Return: `nexsys-hivemind/context/audits/2026-08-22_R9_E3-HEALTH_return.md`.
**Context (as filed).** Every path is auth-gated (`before(*)`), so the readiness probe must authenticate
with the full-access first-run token — a heavy credential for a liveness check, and it breaks
once the operator deletes the token post-pairing.
**Options.** (a) Add an unauthenticated, **loopback-only** `/health` returning lifecycle phase
(200 RUNNING / 503 otherwise). (b) Split `/livez` (process up) + `/readyz` (projection live).
(c) Keep the authed probe (status quo).
**Recommendation.** **(a)** minimal `/health`, loopback-bound, outside the auth filter. The
probe already prefers a non-`/api` health path if present (`HS_HEALTH_PATH`), so this is a
drop-in upgrade with zero packaging change. Until then the authed probe (status quo) is fine.

### E4 — Wire `LinuxSystemPaths` at the composition root (Core)
**Context.** `Main` resolves dirs from `$HOMESYNAPSE_HOME`; it doesn't yet select
`LinuxSystemPaths`, though the selection rule (image at `/opt/homesynapse` + `homesynapse`
user — both already true under the skeleton) is specified in Doc 12 §3.2. Until it's wired,
config + token live under `/var/lib` instead of the FHS `/etc/homesynapse`.
**Options.** (a) Wire the documented detection in the composition root (M13-adjacent). (b) Stay
on `HOMESYNAPSE_HOME` indefinitely.
**Recommendation.** **(a)** at M13 alongside sd_notify. The skeleton already satisfies both
preconditions; the packaging flip is one line in `common.sh` (`HS_TOKEN_FILE`) + dropping the
unit's `HOMESYNAPSE_HOME` line. Non-blocking; track with M13.

### E5 — Fold the jlink image build into Gradle (build-logic)
**Context.** The image is built by `image/build-image.sh` driving `installDist` + `jlink`,
because adding a `jlink` task to `homesynapse.application-conventions` would edit `build-logic/`
— outside this lane's write-isolation. The convention's description already advertises "jlink
packaging."
**Options.** (a) Keep the script (self-contained, in-lane). (b) Add the `org.beryx.jlink`
(badass-jlink) plugin to the application convention so `./gradlew jlink` produces the image
(handles the automatic-module merge; needs a build-logic edit + a pinned plugin version). (c)
`jpackage`-based app-image.
**Recommendation.** **(b)** once the hub approves a build-logic change — it makes the image a
first-class Gradle output and removes the `installDist`→jlink two-step. The script stays as the
fallback / smoke driver. Non-blocking.

### E6 — Write `initial_api_token` with explicit `0600` (Core, defence-in-depth)
**Context.** `OpaqueTokenStore.writeArtifact()` writes the token with no explicit POSIX mode.
The installer mitigates this by creating the config dir `0700`/`homesynapse`, so it is not
exposed — but defence-in-depth wants the file itself `0600`.
**Options.** (a) `Files.write(..., PosixFilePermissions 0600)` (or set perms post-write). (b)
Rely on the `0700` dir (status quo).
**Recommendation.** **(a)** — a tiny, low-risk hardening. Not blocking; the dir perm already
closes the exposure.

### E7 — Repository home for `distribution/`
**Context.** Built in-repo under `homesynapse-core/distribution/` per the dispatch. Could also
live in its own repo.
**Options.** (a) In-repo (current) — install-smoke sees the real Core HEAD every commit; one
clone. (b) Separate repo — cleaner ownership, but the smoke must pin/fetch a Core version and
loses "boots whatever HEAD exists."
**Recommendation.** **(a) keep in-repo** through launch — the whole de-risk is that the install
path tracks the *current* artifact continuously. Re-evaluate post-launch if packaging needs an
independent release cadence.

### E8 — CI wiring (spine-owned `.github/workflows/`)
**Context.** `ci/install-smoke.yml` is the gate, but GitHub only runs workflows under
`.github/workflows/`, which the spine owns (outside this lane).
**Options.** (a) Hub copies/symlinks `distribution/ci/install-smoke.yml` →
`.github/workflows/`. (b) Add a `distribution/**` path-trigger job to the existing `ci.yml`.
**Recommendation.** **(a)** — keeps the job definition in-lane and auditable; the hub does the
one-line wiring. This is the only step between "smoke written" and "gate #4 live."
