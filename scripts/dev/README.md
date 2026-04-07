# HomeSynapse Core — Pi Development Scripts

Shell scripts that automate the deploy-test-observe cycle between your dev machine and a Raspberry Pi 5 development target.

## Purpose

HomeSynapse is built on a dev-machine-to-Pi workflow: code is compiled on your Windows, macOS, or Linux workstation and deployed to a Raspberry Pi 5 for execution against target hardware. These scripts automate that cycle so you spend time writing code, not typing SSH commands.

All scripts work in Git Bash on Windows (MINGW64), zsh on macOS, and bash on Linux.

## Prerequisites

Before using these scripts, complete the [Pi 5 Developer Setup Guide](../../homesynapse-core-docs/operations/pi5-developer-setup-guide.md). Specifically:

1. **Tailscale** running on both your dev machine and the Pi (mesh VPN for stable SSH access)
2. **SSH key authentication** configured — no password prompts
3. **`~/.ssh/config`** on your dev machine contains a `Host pi` alias pointing to your Pi's Tailscale hostname:
   ```
   Host pi
       HostName hs-dev-1
       User homesynapse
   ```
4. **Java 21 (Amazon Corretto)** installed on the Pi with `JAVA_HOME` set in `~/.profile`
5. **NVMe mounted** at `/mnt/nvme` with the FHS directory layout in place

## Quick Start

**Run tests on the Pi** (most common workflow):
```bash
./scripts/dev/pi-health.sh && ./scripts/dev/pi-deploy.sh && ./scripts/dev/pi-test.sh :core:event-bus:check
```

**Pull JFR recordings** for analysis:
```bash
./scripts/dev/pi-jfr-pull.sh
```

**Tests failing weird — start fresh:**
```bash
./scripts/dev/pi-clean.sh && ./scripts/dev/pi-deploy.sh && ./scripts/dev/pi-test.sh check
```

## Scripts

### pi-health.sh — Preflight Health Check

Run before any test session to verify the Pi is ready. Checks SSH connectivity, CPU temperature, memory, NVMe mount status, disk space, Java version, Tailscale status, uptime, and FHS directory integrity. All checks are batched into minimal SSH round trips for speed.

```bash
./scripts/dev/pi-health.sh              # Standard check
./scripts/dev/pi-health.sh --verbose    # Print SSH commands (debug mode)
```

### pi-deploy.sh — Deploy Code to Pi

Builds locally and syncs code to the Pi. Uses `rsync` when available (Linux/macOS), falls back to `tar`+`scp` on Windows/Git Bash where `rsync` is typically absent.

```bash
./scripts/dev/pi-deploy.sh                          # Build and deploy everything
./scripts/dev/pi-deploy.sh --skip-build              # Deploy without rebuilding
./scripts/dev/pi-deploy.sh --module :core:event-bus   # Build and deploy one module
```

### pi-test.sh — Run Tests on Pi Remotely

Executes any Gradle task on the Pi and streams colored output back to your terminal. Returns the remote exit code so CI integrations work correctly.

```bash
./scripts/dev/pi-test.sh :core:event-bus:check
./scripts/dev/pi-test.sh :core:event-model:test --tests "*EventStoreContractTest*"
./scripts/dev/pi-test.sh check    # Full project check
```

### pi-jfr-pull.sh — Pull JFR Recordings

Copies Java Flight Recorder files from the Pi to your dev machine for analysis in JDK Mission Control. Timestamps filenames to prevent overwrites across multiple pulls.

```bash
./scripts/dev/pi-jfr-pull.sh                    # Pull to ./jfr-recordings/
./scripts/dev/pi-jfr-pull.sh --dest ~/analysis   # Pull to custom directory
```

### pi-clean.sh — Clean Pi State

Removes SQLite databases, JFR recordings, and temp files for a fresh test run. Asks for confirmation before deleting anything.

```bash
./scripts/dev/pi-clean.sh            # Interactive — asks for confirmation
./scripts/dev/pi-clean.sh --force    # Skip confirmation
./scripts/dev/pi-clean.sh --all      # Also clean Gradle caches (slow rebuild)
```

## Environment Variables

All scripts accept these environment variables to override defaults:

| Variable | Default | Purpose |
|---|---|---|
| `PI_HOST` | `pi` | SSH host alias (matches `~/.ssh/config`) |
| `PI_USER` | `homesynapse` | Username on the Pi |
| `PI_PROJECT_DIR` | `homesynapse-core` | Remote project directory under `~/` |

Example — targeting a second Pi:
```bash
PI_HOST=pi2 ./scripts/dev/pi-health.sh
```

## Troubleshooting

**"Cannot reach Pi" / SSH timeout:**
Tailscale is probably not running on your dev machine or the Pi. Check `tailscale status` on both. If the Pi lost WiFi, USB-tether your phone to it as a fallback.

**"NVMe NOT MOUNTED":**
The Pi rebooted and the NVMe didn't auto-mount. SSH in manually and run `sudo mount -a`, then check `cat /etc/fstab | grep nvme` to verify the fstab entry exists.

**"Java not found" or wrong version:**
Non-interactive SSH doesn't source `~/.bashrc`. The setup guide puts `JAVA_HOME` in `~/.profile` instead. Verify with `ssh pi "bash -lc 'java -version'"`. If that works but `ssh pi "java -version"` doesn't, the environment is in `.bashrc` instead of `.profile` — move it.

**rsync not found (Windows):**
This is expected. The deploy script automatically falls back to `tar`+`scp`. No action needed.

**Gradle out of memory on Pi:**
The Pi has 4 GB of RAM. Full multi-module builds may fail. Use `--skip-build` on `pi-deploy.sh` to build locally and only deploy compiled output, or target a single module with `--module`.

## Adding a New Pi to the Fleet

To set up `hs-dev-2` (or any additional Pi):

1. Follow the [Pi 5 Developer Setup Guide](../../homesynapse-core-docs/operations/pi5-developer-setup-guide.md) for the new Pi, using hostname `hs-dev-2`
2. Add an SSH config entry on your dev machine:
   ```
   Host pi2
       HostName hs-dev-2
       User homesynapse
   ```
3. Use the new host: `PI_HOST=pi2 ./scripts/dev/pi-health.sh`
