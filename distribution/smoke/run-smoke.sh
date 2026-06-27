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

# ╔══ 3. READINESS PROBE (authed loopback) ═══════════════════════════════════╗
if "${HS_OPT}/libexec/health-probe.sh" --wait --timeout 90 --token-file "${HS_TOKEN_FILE}"; then
    ok "loopback health probe green (HTTP 200 RUNNING)"
else
    bad "health probe never went green"; dump_logs
fi

# ╔══ 4. PAIRING TOKEN MINTED + LOCKED DOWN ══════════════════════════════════╗
if [ -s "${HS_TOKEN_FILE}" ]; then
    ok "first-run pairing token minted at ${HS_TOKEN_FILE}"
    OWNER="$(stat -c '%U' "${HS_TOKEN_FILE}" 2>/dev/null || echo '?')"
    [ "${OWNER}" = "${HS_USER}" ] && ok "token owned by ${HS_USER}" || bad "token owner is ${OWNER}, expected ${HS_USER}"
    # Config dir must not be world/other-accessible (secrets are 0600-class).
    CMODE="$(stat -c '%a' "${HS_CONFIG_DIR}" 2>/dev/null || echo '?')"
    case "${CMODE}" in *0|*00) ok "config dir mode ${CMODE} (no world access)";; *) bad "config dir mode ${CMODE} allows other access";; esac
else
    bad "no pairing token at ${HS_TOKEN_FILE}"
fi

# ╔══ 5. AUTH IS ENFORCED (INV-SE-02) ════════════════════════════════════════╗
# An UNauthenticated request to the API must be rejected (401/403), proving the
# surface is never open even on loopback.
UNAUTH="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' "http://${HS_BIND}:${HS_PORT}/api/v1/entities" 2>/dev/null)"; [ -n "${UNAUTH}" ] || UNAUTH=000
case "${UNAUTH}" in
    401|403) ok "unauthenticated request rejected (${UNAUTH}) — auth enforced" ;;
    200) bad "UNAUTHENTICATED request returned 200 — surface is open!" ;;
    *) bad "unexpected unauth status ${UNAUTH}" ;;
esac

# ╔══ 6. STOP ════════════════════════════════════════════════════════════════╗
if [ "${MODE}" = "systemd" ]; then
    systemctl stop "${HS_UNIT}" && ok "service stopped" || bad "service stop"
    sleep 2
    systemctl is-active --quiet "${HS_UNIT}" && bad "still active after stop" || ok "service inactive after stop"
else
    pkill -TERM -u "${HS_USER}" -f "${HS_LAUNCHER}" 2>/dev/null; sleep 3
    pgrep -u "${HS_USER}" -f "${HS_LAUNCHER}" >/dev/null && bad "process survived SIGTERM" || ok "process exited on SIGTERM"
fi

# ╔══ 7. UNINSTALL (data preserved) ══════════════════════════════════════════╗
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
