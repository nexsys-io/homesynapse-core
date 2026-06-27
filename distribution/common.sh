#!/bin/sh
# HomeSynapse Core — distribution shared constants (single source of truth).
#
# Every script in distribution/ sources this file. Paths here mirror the Locked
# boot contract (Doc 12 §3.2 / LTD-01 / LTD-13) and LinuxSystemPaths. Change a
# path in ONE place.
#
# POSIX sh only — no bashisms — so it is sourceable from dash (Debian /bin/sh),
# maintainer scripts, and the launcher alike.
set -eu

# ── Identity ────────────────────────────────────────────────────────────────
HS_USER="homesynapse"
HS_GROUP="homesynapse"
HS_UNIT="homesynapse.service"

# ── Filesystem layout (FHS; matches LinuxSystemPaths) ───────────────────────
HS_OPT="/opt/homesynapse"               # binaryDir() — read-only runtime image
HS_DATA="/var/lib/homesynapse"          # dataDir()   — event store + (today) config
HS_LOG="/var/log/homesynapse"           # logDir()
HS_ETC="/etc/homesynapse"               # env drop-in today; configDir() after M13
HS_ENV_FILE="${HS_ETC}/homesynapse.env" # operator override drop-in (conffile)

HS_LAUNCHER="${HS_OPT}/bin/homesynapse" # Doc 12 §3.2 names this exact path
HS_RUNTIME="${HS_OPT}/runtime"          # jlinked JRE
HS_LIB="${HS_OPT}/lib"                  # app + dependency jars (classpath)

# ── Runtime base for the CURRENT artifact ───────────────────────────────────
# Main resolves all dirs from $HOMESYNAPSE_HOME (it does NOT yet select
# LinuxSystemPaths — see docs/boot-contract-map.md). So today HOMESYNAPSE_HOME
# points at the data dir, and config/token live UNDER it.
HS_HOME_ENV="${HS_DATA}"                       # exported as HOMESYNAPSE_HOME
HS_CONFIG_DIR="${HS_DATA}/config"              # $HOMESYNAPSE_HOME/config (current Main)
HS_DB_FILE="${HS_DATA}/data/homesynapse-events.db"
HS_TOKEN_FILE="${HS_CONFIG_DIR}/initial_api_token"   # one-line move to $HS_ETC after M13

# ── Network surface (HomeSynapseConfig.HOME_DEFAULT) ────────────────────────
HS_BIND="127.0.0.1"   # loopback default — LAN bind is an explicit Core opt-in (AB-1)
HS_PORT="7070"        # PLAN-M3 §10
# Readiness probe target. Today every path is auth-gated (auth runs before(*)),
# so the probe authenticates with the first-run token against a cheap GET.
# Forward-compatible: if Core later adds an unauthenticated loopback /health
# (see escalation E3), set HS_HEALTH_PATH=/health and the probe skips the token.
HS_HEALTH_PATH="${HS_HEALTH_PATH:-/api/v1/entities}"

# ── Version ─────────────────────────────────────────────────────────────────
# Resolution order: explicit env → distribution/VERSION → git describe → default.
_dist_dir() { CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd || echo "."; }
hs_version() {
    if [ -n "${HS_VERSION:-}" ]; then printf '%s' "${HS_VERSION}"; return; fi
    _d="$(_dist_dir)"
    if [ -f "${_d}/VERSION" ]; then tr -d ' \n' < "${_d}/VERSION"; return; fi
    if [ -f "${_d}/../VERSION" ]; then tr -d ' \n' < "${_d}/../VERSION"; return; fi
    if command -v git >/dev/null 2>&1 && git -C "${_d}" rev-parse >/dev/null 2>&1; then
        _v="$(git -C "${_d}" describe --tags --always --dirty 2>/dev/null)"
        if [ -n "${_v}" ]; then
            # A Debian Version field MUST start with a digit (dpkg-deb rejects otherwise).
            # A tag-derived describe (1.2.3, 1.2.3-5-gabc1234) already does; a bare commit
            # id from an untagged repo (git describe --always -> b85e1ed) does NOT, so wrap
            # it as a 0.1.0 upstream + the +g<id> git build-metadata convention. This is the
            # install-smoke gate-4 fix: SHAs starting with a-f assembled an invalid .deb.
            case "${_v}" in
                [0-9]*) printf '%s' "${_v}" ;;
                *)      printf '0.1.0+g%s' "${_v}" ;;
            esac
            return
        fi
    fi
    printf '0.1.0-skeleton'
}

# ── Architecture (Debian arch names) ────────────────────────────────────────
# The image bundles a jlinked JRE, so artifacts are arch-specific. Default to the
# host; cross-arch (e.g. building arm64 on amd64) is an open escalation (E1).
hs_deb_arch() {
    if [ -n "${HS_ARCH:-}" ]; then printf '%s' "${HS_ARCH}"; return; fi
    if command -v dpkg >/dev/null 2>&1; then dpkg --print-architecture; return; fi
    case "$(uname -m)" in
        x86_64|amd64) printf 'amd64' ;;
        aarch64|arm64) printf 'arm64' ;;
        armv7l|armhf) printf 'armhf' ;;
        *) uname -m ;;
    esac
}

# ── LTD-01 JVM flags (the Locked launcher contract) ─────────────────────────
HS_JVM_FLAGS="-Xms512m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Xss512k -XX:CICompilerCount=2 -XX:+UseStringDeduplication -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=128m"

# ── ExitCode contract (app/.../ExitCode.java) ───────────────────────────────
# 10 CONFIGURATION_FAILURE (deterministic — do NOT auto-restart)
# 11 PERSISTENCE_FAILURE  12 EVENT_BUS_FAILURE  13 SUBSYSTEM_INIT_TIMEOUT
# 99 UNEXPECTED_ERROR
HS_EXIT_CONFIG_FAILURE="10"
