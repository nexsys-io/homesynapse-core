#!/usr/bin/env bash
#
# run-smoke.sh — the install-smoke gate (go/no-go #4: "install path proven").
#
#   install → boot → loopback health probe → assert RUNNING + token minted
#           → assert auth is enforced → stop → uninstall → assert data preserved.
#
# Two modes:
#   (default)     .deb + systemd      — clean-machine install on a systemd host
#                                       (GitHub ubuntu runners qualify).
#   --no-systemd  tarball + install.sh — direct-launch path for plain containers
#                                       and local dev (no systemd PID1 needed).
#
# Fast by design (CI gate). The 72h soak is the on-device validation lane.
set -uo pipefail   # NOTE: not -e; we want to run all checks and report.

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
# shellcheck source=../common.sh
. "${DIST}/common.sh"

MODE="systemd"
[ "${1:-}" = "--no-systemd" ] && MODE="no-systemd"

FAILS=0
log()  { printf '[smoke] %s\n' "$*" >&2; }
ok()   { printf '[smoke] PASS  %s\n' "$*" >&2; }
bad()  { printf '[smoke] FAIL  %s\n' "$*" >&2; FAILS=$((FAILS+1)); }
have_systemd() { [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; }

[ "$(id -u)" -eq 0 ] || { log "must run as root"; exit 2; }
[ "${MODE}" = "systemd" ] && ! have_systemd && { log "no systemd present — falling back to --no-systemd"; MODE="no-systemd"; }
log "mode=${MODE}"

dump_logs() {
    log "──── diagnostics ────"
    if have_systemd; then systemctl --no-pager status "${HS_UNIT}" 2>&1 | sed 's/^/  /' >&2 || true
        journalctl -u "${HS_UNIT}" -b --no-pager 2>&1 | tail -40 | sed 's/^/  /' >&2 || true
    fi
    [ -f /var/log/homesynapse-stdout.log ] && tail -40 /var/log/homesynapse-stdout.log | sed 's/^/  /' >&2 || true
}

# ── Build artifacts if absent ───────────────────────────────────────────────
if [ "${MODE}" = "systemd" ]; then
    DEB="$(ls -1t "${DIST}"/deb/build/homesynapse_*.deb 2>/dev/null | head -1 || true)"
    [ -n "${DEB}" ] || { log "building .deb"; DEB="$("${DIST}/deb/build-deb.sh" | tail -1)"; }
    log "deb=${DEB}"
else
    TARBALL="$(ls -1t "${DIST}"/image/build/homesynapse_*.tar.gz 2>/dev/null | head -1 || true)"
    [ -n "${TARBALL}" ] || { log "building image"; "${DIST}/image/build-image.sh"; TARBALL="$(ls -1t "${DIST}"/image/build/homesynapse_*.tar.gz | head -1)"; }
    log "tarball=${TARBALL}"
fi

# ╔══ 1. INSTALL ═════════════════════════════════════════════════════════════╗
if [ "${MODE}" = "systemd" ]; then
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends "${DEB}" >/dev/null 2>&1 || apt-get install -y "${DEB}"
    else
        dpkg -i "${DEB}" || { apt-get -f install -y || true; dpkg -i "${DEB}"; }
    fi
    [ $? -eq 0 ] && ok "package installed" || bad "package install"
else
    "${DIST}/install/install.sh" "${TARBALL}" && ok "tarball installed" || bad "tarball install"
fi

# ╔══ 2. SERVICE ACTIVE / RUNNING ════════════════════════════════════════════╗
if [ "${MODE}" = "systemd" ]; then
    if systemctl is-active --quiet "${HS_UNIT}"; then ok "unit is active"; else bad "unit not active"; dump_logs; fi
    systemctl is-enabled --quiet "${HS_UNIT}" && ok "unit is enabled (starts on boot)" || bad "unit not enabled"
fi

# ╔══ 3. READINESS PROBE (authed loopback — the minted token validates) ══════╗
if "${HS_OPT}/libexec/health-probe.sh" --wait --timeout 90 --token-file "${HS_TOKEN_FILE}"; then
    ok "loopback health probe green (HTTP 200 RUNNING)"
else
    bad "health probe never went green"; dump_logs
fi

# ╔══ 3b. UNAUTHENTICATED LOOPBACK /health (the unit's probe path, E3) ═══════╗
# The SAME probe binary the unit's ExecStartPost runs, with the SAME path and NO
# token file (H13: one instrument, two rigs). 200 = the state projection is LIVE.
# passes-but-false input: a /health that returns 200 regardless of projection
# mode — bounded by HealthEndpointTest's 503 pin and by check 3's authed 200 (the
# gate and the endpoint read one ReadinessSource).
if "${HS_OPT}/libexec/health-probe.sh" --wait --timeout 30 --health-path "${HS_HEALTH_PATH}"; then
    ok "unauthenticated loopback /health green (HTTP 200 — the unit's probe path, E3)"
else
    bad "unauthenticated loopback ${HS_HEALTH_PATH} never went green"; dump_logs
fi

# ╔══ 4. EVENT WRITE PATH IS LIVE ════════════════════════════════════════════╗
# The F-23 class: an artifact can boot, go RUNNING, and serve health/auth/
# dashboard while the event-sourced spine is dead (jdk.jfr absent from the
# jlinked runtime — every publish's metrics emission throws uncaught). Assert
# the property, not the proxy: this boot PERSISTED events, and the service
# logs carry ZERO uncaught-throw signatures on the jdk.jfr/BusMetrics paths.
# passes-but-false input: an artifact that persists events but throws only on
# swallowed JFR paths (probe 2 exists for it); a runtime whose boot
# legitimately persists zero rows (P6 refutes this at source — if P6 fails,
# this check's form is wrong: STOP).
# ── probe 1: the events DB under the pinned data path has ≥1 committed row ──
if ! command -v sqlite3 >/dev/null 2>&1; then
    bad "sqlite3 unavailable — cannot probe the events DB (install sqlite3)"
elif [ ! -f "${HS_DB_FILE}" ]; then
    bad "events DB absent at ${HS_DB_FILE} — nothing persisted this boot"; dump_logs
else
    EVENT_ROWS="$(sqlite3 "file:${HS_DB_FILE}?mode=ro" 'SELECT COUNT(*) FROM events;' 2>/dev/null || true)"
    case "${EVENT_ROWS}" in
        ''|*[!0-9]*) bad "events row count unreadable at ${HS_DB_FILE} (got '${EVENT_ROWS}')"; dump_logs ;;
        0) bad "event write path DEAD — 0 events rows after a healthy-looking boot"; dump_logs ;;
        *) ok "event write path persisted ${EVENT_ROWS} event row(s) this boot (${HS_DB_FILE})" ;;
    esac
fi
# ── probe 2: zero uncaught-throw signatures in the service logs ─────────────
# Pinned real signature (H3 journal, 6×/boot on the broken artifact):
#   java.lang.NoClassDefFoundError: jdk/jfr/Event
#   Caused by: java.lang.ClassNotFoundException: jdk.jfr.Event
# The unescaped dot nets BOTH jdk/jfr and jdk.jfr spellings; the bare
# NoClassDefFoundError arm is the generic second net.
THROW_RE='NoClassDefFoundError|jdk.jfr|BusMetrics'
THROW_HITS=0
LOG_SOURCES=0
if have_systemd; then
    JHITS="$(journalctl -u "${HS_UNIT}" -b --no-pager 2>/dev/null | grep -icE "${THROW_RE}" || true)"
    THROW_HITS=$((THROW_HITS + ${JHITS:-0})); LOG_SOURCES=$((LOG_SOURCES + 1))
fi
if [ -f /var/log/homesynapse-stdout.log ]; then
    SHITS="$(grep -icE "${THROW_RE}" /var/log/homesynapse-stdout.log || true)"
    THROW_HITS=$((THROW_HITS + ${SHITS:-0})); LOG_SOURCES=$((LOG_SOURCES + 1))
fi
if [ "${LOG_SOURCES}" -eq 0 ]; then
    bad "no log source to scan (no journal, no /var/log/homesynapse-stdout.log) — the throw probe cannot run"
elif [ "${THROW_HITS}" -eq 0 ]; then
    ok "zero uncaught-throw signatures across ${LOG_SOURCES} log source(s) (grep -icE '${THROW_RE}' = 0)"
else
    bad "${THROW_HITS} uncaught-throw signature line(s) in service logs (pattern: ${THROW_RE})"; dump_logs
fi

# ╔══ 5. PAIRING TOKEN MINTED + LOCKED DOWN ══════════════════════════════════╗
if [ -s "${HS_TOKEN_FILE}" ]; then
    ok "first-run pairing token minted at ${HS_TOKEN_FILE}"
    OWNER="$(stat -c '%U' "${HS_TOKEN_FILE}" 2>/dev/null || echo '?')"
    [ "${OWNER}" = "${HS_USER}" ] && ok "token owned by ${HS_USER}" || bad "token owner is ${OWNER}, expected ${HS_USER}"
    # Config dir must not be world/other-accessible (secrets are 0600-class).
    CMODE="$(stat -c '%a' "${HS_CONFIG_DIR}" 2>/dev/null || echo '?')"
    case "${CMODE}" in *0|*00) ok "config dir mode ${CMODE} (no world access)";; *) bad "config dir mode ${CMODE} allows other access";; esac
    # OR-TOKEN-MODE-644: the mint writes 0600 at HEAD (OpaqueTokenStore.writeOwnerOnlyAtomically);
    # this check makes CI structurally able to SEE a regression — the 2026-08-13 vintage
    # shipped 644 and stayed green (the card-sitting F-S10).
    FMODE="$(stat -c '%a' "${HS_TOKEN_FILE}" 2>/dev/null || echo '?')"
    [ "${FMODE}" = "600" ] && ok "pairing token mode 600 (owner-only)" || bad "pairing token mode ${FMODE}, expected 600"
    FMODE="$(stat -c '%a' "${HS_CONFIG_DIR}/api_tokens" 2>/dev/null || echo '?')"
    [ "${FMODE}" = "600" ] && ok "token store (api_tokens) mode 600 (owner-only)" || bad "token store (api_tokens) mode ${FMODE}, expected 600"
else
    bad "no pairing token at ${HS_TOKEN_FILE}"
fi

# ╔══ 6. AUTH IS ENFORCED (INV-SE-02) ════════════════════════════════════════╗
# An UNauthenticated request to the API must be rejected (401/403), proving the
# surface is never open even on loopback.
UNAUTH="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' "http://${HS_BIND}:${HS_PORT}/api/v1/entities" 2>/dev/null)"; [ -n "${UNAUTH}" ] || UNAUTH=000
case "${UNAUTH}" in
    401|403) ok "unauthenticated request rejected (${UNAUTH}) — auth enforced" ;;
    200) bad "UNAUTHENTICATED request returned 200 — surface is open!" ;;
    *) bad "unexpected unauth status ${UNAUTH}" ;;
esac

# ╔══ 7. DASHBOARD SERVE PATH (DASH-SERVE: B-1/B-2/B-3) ══════════════════════╗
# The static shell must serve WITHOUT auth (posture (A)): packaging (B-2),
# mount (B-1), and the exemption (B-3) are one seam — any broken hop blanks
# the browser. Asserted on every push so the seam class stays detected (L4).
ROOT_REDIRECT="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' "http://${HS_BIND}:${HS_PORT}/" 2>/dev/null)"; [ -n "${ROOT_REDIRECT}" ] || ROOT_REDIRECT=000
if [ "${ROOT_REDIRECT}" = "302" ]; then
    ok "headerless GET / redirects to the dashboard (302)"
else
    bad "dashboard serve path broken — B-1/B-2/B-3 class: GET / returned ${ROOT_REDIRECT}, expected 302"
fi
DASH_SHELL="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' "http://${HS_BIND}:${HS_PORT}/dashboard/" 2>/dev/null)"; [ -n "${DASH_SHELL}" ] || DASH_SHELL=000
if [ "${DASH_SHELL}" = "200" ]; then
    ok "headerless GET /dashboard/ serves the shell (200)"
else
    bad "dashboard serve path broken — B-1/B-2/B-3 class: GET /dashboard/ returned ${DASH_SHELL}, expected 200"
fi

# ╔══ 8. STOP ════════════════════════════════════════════════════════════════╗
if [ "${MODE}" = "systemd" ]; then
    systemctl stop "${HS_UNIT}" && ok "service stopped" || bad "service stop"
    sleep 2
    systemctl is-active --quiet "${HS_UNIT}" && bad "still active after stop" || ok "service inactive after stop"
    # FAILCHAN §6-B (R-10 Row 6 (a)): a clean stop must GRADE clean. The JVM exits 143 after
    # its SIGTERM hook; without SuccessExitStatus=143 systemd graded every clean stop
    # Result=exit-code / ActiveState=failed (measured twice: R-3a §6-B; O-2 on hs-fresh,
    # 2026-09-03). The O-2 measurement inverted into a gate — the mechanical confirmation on
    # this runner and, on the card, R-4b's first `systemctl stop`.
    RES="$(systemctl show -p Result --value "${HS_UNIT}" 2>/dev/null || true)"
    ST="$(systemctl show -p ActiveState --value "${HS_UNIT}" 2>/dev/null || true)"
    EX="$(systemctl show -p ExecMainStatus --value "${HS_UNIT}" 2>/dev/null || true)"
    if [ "${RES}" = "success" ] && [ "${ST}" = "inactive" ]; then
        ok "clean stop grades success (Result=${RES} ExecMainStatus=${EX})"
    else
        bad "clean stop graded ${RES}/${ST} (ExecMainStatus=${EX}) — the §6-B lie"; dump_logs
    fi
else
    pkill -TERM -u "${HS_USER}" -f "${HS_LAUNCHER}" 2>/dev/null; sleep 3
    pgrep -u "${HS_USER}" -f "${HS_LAUNCHER}" >/dev/null && bad "process survived SIGTERM" || ok "process exited on SIGTERM"
fi

# ╔══ 9. UNINSTALL (data preserved) ══════════════════════════════════════════╗
if [ "${MODE}" = "systemd" ]; then
    if command -v apt-get >/dev/null 2>&1; then apt-get remove -y homesynapse >/dev/null 2>&1 || dpkg -r homesynapse
    else dpkg -r homesynapse; fi
    [ $? -eq 0 ] && ok "package removed" || bad "package remove"
    [ -e "/lib/systemd/system/${HS_UNIT}" ] && bad "unit file lingered after remove" || ok "unit file gone"
    [ -d "${HS_OPT}" ] && bad "image dir lingered after remove" || ok "image dir gone"
    [ -d "${HS_DATA}" ] && ok "data dir preserved on remove (event store safe)" || bad "data dir vanished on remove!"
else
    log "(no-systemd) skipping dpkg remove; image at ${HS_OPT}, data at ${HS_DATA}"
fi

# ╔══ Verdict ════════════════════════════════════════════════════════════════╗
echo "────────────────────────────────────────────────────────" >&2
if [ "${FAILS}" -eq 0 ]; then
    log "INSTALL-SMOKE PASSED ✓  (gate #4: install path proven)"; exit 0
else
    log "INSTALL-SMOKE FAILED ✗  (${FAILS} check(s) failed)"; exit 1
fi
