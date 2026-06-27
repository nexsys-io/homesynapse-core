# HomeSynapse Core — Distribution Workspace

**Lane:** Distribution / one-command install (devops-packaging).
**Mode:** de-risking **skeleton now**, device-pairing **ramp after M9**.
**Write-isolation:** everything here lives under `homesynapse-core/distribution/…`. This
lane never edits core Java, the `web-ui` tree, the hivemind spine, or design docs. Where
packaging would need a Core change, it is **surfaced** (see `docs/escalations.md`), not made.

---

## Why this exists

The classic launch failure is *"the installer doesn't work and it's November."* This
workspace removes it by standing up the **whole install path now** — around the *current*
runnable artifact (core `5363347`, M7.3) — so the path is exercised continuously in CI
months before launch, not first-attempted under deadline.

The install-skeleton is **go/no-go gate #4** ("install path proven") for the mid-August
review. Booting the artifact as a service end-to-end *is* the de-risk.

## What "one command" means today

```
sudo apt install ./homesynapse_<version>_<arch>.deb
#   ↳ lays down the runtime image, creates the homesynapse user + state dirs,
#     installs + enables + starts the systemd service, waits for health,
#     and prints the first-run pairing-token path.
```

…or, with no dpkg:

```
sudo distribution/install/install.sh ./homesynapse_<version>_<arch>.tar.gz
```

After it returns, the operator has a running, loopback-bound, authenticated service and a
printed path to the one-time pairing token — the handoff point where the **post-M9 pairing
wizard** will later take over.

---

## The pieces (and how they compose)

```
 build-image.sh ──> /opt/homesynapse  (jlinked JRE + app jars + launcher)   [image/]
        │
        ├─ build-deb.sh ──> homesynapse_<v>_<arch>.deb                       [deb/]
        │        └─ postinst: user+dirs+perms, enable+start, print token
        │
        ├─ install.sh  ──> same layout, no dpkg (curl|sh / offline tarball)  [install/]
        │
        ├─ update.sh   ──> stop → swap image → restart, store preserved      [update/]
        │
        ├─ homesynapse.service ──> least-privilege systemd unit              [systemd/]
        │        └─ ExecStartPost: loopback HTTP health probe (readiness)
        │
        └─ run-smoke.sh ──> install→boot→probe→assert→uninstall (CI gate)    [smoke/]
                 └─ health-probe.sh  (shared; used by unit + smoke + update)
```

`common.sh` is the **single source of truth** for paths, port, user, and version. Every
script sources it; change a path in one place.

| Directory   | Contents |
|-------------|----------|
| `image/`    | `build-image.sh` — Gradle `installDist` → jlink runtime → launcher. |
| `systemd/`  | `homesynapse.service` + `homesynapse.env.example` env drop-in. |
| `deb/`      | `build-deb.sh` + `debian/` control & maintainer scripts. |
| `install/`  | `install.sh` — dpkg-free installer over a self-contained tarball. |
| `update/`   | `update.sh` — non-destructive, store-preserving version swap. |
| `smoke/`    | `run-smoke.sh` (install-smoke) + `health-probe.sh` (shared probe). |
| `ci/`       | `install-smoke.yml` — the CI gate job (wiring seam noted inside). |
| `docs/`     | architecture, the post-M9 pairing seam, escalations, the boot contract map. |

---

## The runtime image layout (`/opt/homesynapse`)

```
/opt/homesynapse/
├── bin/homesynapse        # launcher — applies the LTD-01 JVM flags, runs the bundled JRE
├── runtime/               # jlinked custom JRE (arch-specific; no system Java required)
├── lib/*.jar              # app + dependency jars (run on the classpath under runtime/)
├── VERSION                # version string (also stamped into the .deb)
└── MANIFEST.sha256        # checksums of runtime/ + lib/ for reproducibility / verify
```

This mirrors `LinuxSystemPaths.binaryDir()` (`/opt/homesynapse`, read-only) and the
launcher path the Locked boot contract (Doc 12 §3.2, LTD-01) names:
`/opt/homesynapse/bin/homesynapse`.

## State & config layout

| Path | Role | Today (skeleton) | After M13 (`LinuxSystemPaths` wired) |
|------|------|------------------|--------------------------------------|
| `/opt/homesynapse`     | read-only image | image            | image (unchanged) |
| `/var/lib/homesynapse` | data / event store | `HOMESYNAPSE_HOME` → holds `config/` + `data/` | `dataDir()` |
| `/etc/homesynapse`     | config | env drop-in only | `configDir()` (incl. `initial_api_token`) |
| `/var/log/homesynapse` | logs | journald (stdout) | `logDir()` + `homesynapse.log` |

**The single most important seam:** the *current* `Main` resolves all runtime dirs from
`$HOMESYNAPSE_HOME` (it does not yet select `LinuxSystemPaths`). So today the unit sets
`HOMESYNAPSE_HOME=/var/lib/homesynapse` and the pairing token lands at
`/var/lib/homesynapse/config/initial_api_token`. When the composition root wires
`LinuxSystemPaths` (M13), config (and the token) move to `/etc/homesynapse`. That move is a
**one-line change** in `common.sh` (`HS_TOKEN_FILE`) plus dropping the `HOMESYNAPSE_HOME`
line from the unit — by design. See `docs/boot-contract-map.md`.

---

## Disciplines this workspace holds

- **Boots the *current* artifact** — value comes from working today, on whatever Core HEAD exists.
- **Local-first / offline install** — the image bundles its own JRE and every jar; nothing is fetched at install time.
- **No destructive migration** — updates preserve the append-only event store + config; the app's own migration runner moves schema forward on start. The updater never rewrites the DB.
- **Least privilege** — dedicated `homesynapse` user, `0600` secrets, loopback-only by default; LAN bind is an explicit, documented, authenticated opt-in (mirrors AB-1).
- **Reproducibility** — pinned JDK 21 (Corretto in CI) and tool versions; the image build is deterministic and self-checksumming.
- **CI is the gate of record** — `ci/install-smoke.yml` proves install→boot→health→stop→uninstall on every change.

See `docs/` for the boot-contract map, the post-M9 pairing seam, and open escalations.
