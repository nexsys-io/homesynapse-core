# Boot-contract map — what the skeleton packages, and the source it's verified against

This is the evidence trail: every packaging decision below is anchored to a line in
the *current* artifact (core `5363347`, M7.3) or the Locked design (Doc 12). It exists so
the hub can trust that the skeleton boots the real thing, not an assumption.

## Entry & launcher

| Decision | Value | Source |
|---|---|---|
| Main class | `com.homesynapse.app.Main` | `app/homesynapse-app/.../Main.java` |
| App module | `com.homesynapse.app` (JPMS; every module is explicit) | `app/.../module-info.java` |
| Launcher path | `/opt/homesynapse/bin/homesynapse` | Doc 12 §3.2 (Step 0.1) |
| JVM flags (LTD-01) | `-Xms512m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Xss512k -XX:CICompilerCount=2 -XX:+UseStringDeduplication -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=128m` | Doc 12 §3.2, LTD-01 |
| Runtime | JDK 21 (Corretto in CI) | `gradle/libs.versions.toml` (`java-language = "21"`) |

The launcher applies the LTD-01 flags and runs the app on the classpath inside the bundled
jlinked JRE (see `image/build-image.sh` for *why classpath, not a full modular image*).

## Paths — the one seam that matters

`Main.resolveBaseDir()` reads **`$HOMESYNAPSE_HOME`**, else `<user.dir>/.homesynapse`; it
then derives `config/` and `data/homesynapse-events.db` under that base. It does **not** yet
select `LinuxSystemPaths` — the comment says *"PlatformPaths-based resolution
(LinuxSystemPaths/LocalPaths) replaces this when those impls are wired."* (`Main.java`).

So the skeleton sets `HOMESYNAPSE_HOME=/var/lib/homesynapse` in the unit, and:

| Role | Skeleton (today) | After M13 (`LinuxSystemPaths` wired) | Source |
|---|---|---|---|
| image (read-only) | `/opt/homesynapse` | `/opt/homesynapse` | `LinuxSystemPaths.binaryDir()` |
| data / event store | `/var/lib/homesynapse/data` | `/var/lib/homesynapse` | `dataDir()` |
| config + token | `/var/lib/homesynapse/config` | `/etc/homesynapse` | `configDir()` |
| logs | journald (stdout) | `/var/log/homesynapse` | `logDir()` |

`LinuxSystemPaths` selection rule (Doc 12 §3.2 / §10): *"if `/opt/homesynapse/` exists and
the process runs as the `homesynapse` user, select `LinuxSystemPaths`."* The skeleton already
satisfies **both** preconditions (image at `/opt/homesynapse`, service runs as `homesynapse`)
— so when the composition root wires the selection, the FHS split activates with a one-line
change in `common.sh` (`HS_TOKEN_FILE`) and dropping the unit's `HOMESYNAPSE_HOME` line.
**This is escalation E4** (wire `LinuxSystemPaths` at the composition root).

## Network surface

| Decision | Value | Source |
|---|---|---|
| Port | `7070` | `HomeSynapseConfig.HOME_DEFAULT` (PLAN-M3 §10) |
| Bind host | `127.0.0.1` (loopback) by default; LAN bind is an explicit opt-in | `HomeSynapseConfig` (AB-1, A1) |
| Surface comes up in | Phase 5 `EXTERNAL_INTERFACES`, before READY | `HomeSynapseCore.start()` line 483 → `bringUpHttpSurface()` |

Note: the stale Javadoc on `isHttpExposed()`/`boundHttpPort()` ("AB-3 does not expose it")
predates AB-1; `start()` **does** bind the surface at Phase 5 now. Confirmed by reading the
method body, not the comment.

## Serial coordinator access — the measured device posture

| Decision | Value | Source |
|---|---|---|
| Serial-device posture (Zigbee coordinator) | class-based `DeviceAllow=char-ttyUSB rw` + `DeviceAllow=char-ttyACM rw` (majors 188/166 — class rules survive replug renumbering where a node path would not) + `SupplementaryGroups=dialout` + `PrivateDevices=no` / `DevicePolicy=closed`, measured R-3a 2026-08-30 (`zigbee.network_resumed: channel=20 panId=0x774c` on the held card). What STAYS hardened: `ProtectSystem=strict`, `PrivateTmp=yes`, the syscall filter (`SystemCallFilter=@system-service` / `SystemCallErrorNumber=EPERM`), `RestrictAddressFamilies` — all unchanged. | the provenance comment above the device lines in `distribution/systemd/homesynapse.service`; measurement: nexsys-hivemind `context/audits/2026-08-30_R3a_rehearsal_operator-record.md` §7/§9 |

## First-run pairing token

| Decision | Value | Source |
|---|---|---|
| Artifact name | `initial_api_token` | `OpaqueTokenStore.INITIAL_TOKEN_ARTIFACT` |
| Location | `<configDir>/initial_api_token` → today `/var/lib/homesynapse/config/initial_api_token` | `OpaqueTokenStore` ctor |
| When minted | first run only (empty store) — `ensureInitialToken()` no-ops once any token exists | `OpaqueTokenStore.ensureInitialToken()` |
| Scope | full access (`SCOPE_ALL`) | `ensureInitialToken()` |
| **Perms** | written with **no explicit POSIX mode** (`Files.writeString`, default umask) | `OpaqueTokenStore.writeArtifact()` |

⇒ Because the app does **not** chmod the token, the **installer** owns confidentiality: the
config dir is created `0700` owned by `homesynapse`, so the token is unreadable by other
users regardless. (A Core change to write the token `0600` explicitly is **escalation E6** —
defence in depth.)

## Health / readiness — an unauthenticated loopback `/health` probe, no `Type=notify`

1. **Every data path is authenticated; `/health` is the one loopback exemption.**
   `RestFilters.installAuth` registers a `before(*)` filter covering `/api/*`, `/internal/*`,
   and every other path (INV-SE-02). Since R-9 (2026-08-22) the filter exempts exactly
   `GET`/`HEAD /health` for **loopback callers only** (R-H1): `200` ⇔ the state projection is
   `LIVE`, `503` = up-but-not-ready (body `{"status":"<mode>"}`, `Cache-Control: no-store`);
   off loopback the same path still needs a token. ⇒ the unit's readiness gate is
   `ExecStartPost=… health-probe.sh --wait --timeout 90 --health-path /health` — it reads
   **no** token, so the first-run pairing artifact may be deleted after pairing without
   affecting any restart; a `401/403` on `/health` is a start failure, never a silent pass.
   The authenticated `GET /api/v1/entities` probe survives only as `run-smoke.sh` check 3,
   which proves the minted token validates. **Escalation E3 is CLOSED at R-9** — return:
   `nexsys-hivemind/context/audits/2026-08-22_R9_E3-HEALTH_return.md`.
2. **`sd_notify` cannot work today, and the `Type=notify` block is a DANGER note, not a
   staged flip.** `SystemdHealthReporter` is implemented but (a) the composition root doesn't
   select it yet ("…responsibility (lifecycle / M13)") and (b) it throws on JDK 21 — *"AF_UNIX
   SOCK_DGRAM is unsupported (JEP 380 stream-only) … deferred to M13."* `NoOpHealthReporter`
   is the live path. ⇒ the unit is `Type=exec` with the `ExecStartPost` HTTP probe, **not**
   `Type=notify`/`WatchdogSec`. Enabling the commented block today bricks the service (systemd
   holds the unit `activating` until `TimeoutStartSec`, then start-limits it — OR-M13-SDNOTIFY
   is HELD; R10-IN-L); the unit's own comment carries the mechanism and the precondition (a
   working sd_notify transport proven on the target JDK).

## Exit codes → restart policy

`ExitCode.java`: `10` CONFIGURATION_FAILURE · `11` PERSISTENCE_FAILURE · `12` EVENT_BUS_FAILURE
· `13` SUBSYSTEM_INIT_TIMEOUT · `99` UNEXPECTED_ERROR. Its Javadoc: *"The systemd unit file
can use these codes to distinguish between restartable and non-restartable failures."*

⇒ Unit: `Restart=on-failure`, `RestartSec=10` (Doc 12 §6.4 / LTD-13), with
`RestartPreventExitStatus=10` — a configuration failure is deterministic and must be surfaced,
not crash-looped. `StartLimitBurst=5/300s` bounds any loop; `MemoryMax=2G` per Doc 12 §6.6.

## Shutdown

`Main` installs a SIGTERM shutdown hook → `SystemLifecycleManager.shutdown("SIGTERM")`; Doc 12
§7 bounds the grace period at 30s (internal), within systemd's `TimeoutStopSec=90`. Unit uses
the default `KillSignal=SIGTERM`.

## Update safety (no destructive migration)

The event store is append-only and event-sourced. Schema evolves via an additive migration
framework — `MigrationRunner` + `hs_schema_version`, migrations `V001…V005` (e.g. V005 *adds*
payload-encryption columns). ⇒ `update.sh` only swaps `/opt/homesynapse`; it never touches
`/var/lib/homesynapse`. The app migrates schema forward on start; the updater never rewrites
the DB. `update-smoke.sh` asserts `COUNT(*) FROM events` is non-decreasing, `home_id` is
stable, and `PRAGMA integrity_check = ok` across a version bump.
