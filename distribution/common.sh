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
# Readiness probe target. Core serves an UNauthenticated loopback /health (E3
# closed at R-9, 2026-08-22): 200 = the state projection is LIVE, 503 = up but
# not ready; the probe sends no token for it. The authenticated path
# (/api/v1/entities + --token-file) is what run-smoke.sh check 3 still probes,
# BY NAME, to prove the minted token validates.
HS_HEALTH_PATH="${HS_HEALTH_PATH:-/health}"

# ── Version ─────────────────────────────────────────────────────────────────
# Resolution order: explicit env → git (the commit decides, whatever the cwd) →
# distribution/VERSION → ../VERSION (the git-less carrier: a tarball export, a
# container without git) → default. HS_DIST_DIR pins the lookup dir when this file
# is sourced under `bash -c` ($0 is then `bash`, not a path, so _dist_dir would
# resolve the CWD — the build scripts pass HS_DIST_DIR="${DIST}" for that reason).
_dist_dir() {
    if [ -n "${HS_DIST_DIR:-}" ]; then printf '%s' "${HS_DIST_DIR}"; return; fi
    CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd || echo "."
}
hs_version() {
    if [ -n "${HS_VERSION:-}" ]; then printf '%s' "${HS_VERSION}"; return; fi
    _d="$(_dist_dir)"
    if command -v git >/dev/null 2>&1 && git -C "${_d}" rev-parse >/dev/null 2>&1; then
        _v="$(git -C "${_d}" describe --tags --always --dirty 2>/dev/null)"
        if [ -n "${_v}" ]; then
            # The scheme (R-V, nexsys-hivemind context/audits/2026-08-22_R7_intake_two-layer-audit_v55-beat-6.md §2 H-2):
            # a tag-shaped describe (1.2.3, 1.2.3-5-gabc1234, 1.2.3-dirty) always carries a
            # dot and passes through; a bare id (7c9e4fa, 7c9e4fa-dirty) never does and is
            # wrapped as 0.1.0+git<YYYYMMDD.HHMMSS>.g<id>, the committer date in UTC.
            # WHY it orders: a Debian Version must start with a digit (a bare id sorts as a
            # NUMBER above every 0.x.y — F-V1, the 2026-08-22 Block-0 build printed 7c9e4fa
            # BARE); the former 0.1.0+g<id> form did not order between builds (dpkg compares
            # g7c9e4fa and gd26777c as strings); +git… sorts ABOVE every +g… build because g
            # is a proper prefix of git; two +git builds order by committer time, monotone
            # along main (a rebase re-stamps %cd, which is the right clock for ordering);
            # depth-free (rev-list --count is a constant 1 on a shallow CI checkout; the
            # commit carries its own date); reproducible (commit date, never build date).
            # A -dirty suffix rides through (…g<id>-dirty). FAIL-CLOSED: a missing or
            # malformed date prints '' — build-image.sh dies on it and dpkg-deb rejects an
            # empty Version — never 0.1.0+g<id>, which is lawful-looking but sorts BELOW
            # every +git build and would re-open the downgrade trap. Tags must be
            # digit-leading (1.2.3, never v1.2.3): build-image.sh asserts the grammar
            # ^[0-9]+\.[0-9]+\.[0-9]+ on the result; smoke/version-grammar-test.sh pins this arm.
            case "${_v}" in
                *.*) printf '%s' "${_v}" ;;
                *)
                    _cd="$(TZ=UTC git -C "${_d}" log -1 --format=%cd --date=format-local:%Y%m%d.%H%M%S 2>/dev/null || true)"
                    case "${_cd}" in
                        [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9])
                            printf '0.1.0+git%s.g%s' "${_cd}" "${_v}" ;;
                        *) printf '' ;;
                    esac ;;
            esac
            return
        fi
    fi
    if [ -f "${_d}/VERSION" ]; then tr -d ' \n' < "${_d}/VERSION"; return; fi
    if [ -f "${_d}/../VERSION" ]; then tr -d ' \n' < "${_d}/../VERSION"; return; fi
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
