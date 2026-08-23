#!/usr/bin/env bash
#
# update.sh — non-destructive, store-preserving version swap.
#
#   sudo distribution/update/update.sh NEW_TARBALL
#
# Contract (Doc 12 + the no-destructive-migration anti-requirement):
#   stop → snapshot old image → swap in the new image → start → readiness-gate.
#   /var/lib/homesynapse (the append-only event store + config) is NEVER touched.
#   The app's own MigrationRunner moves the SCHEMA forward on start (additive
#   V001…V00N); this script never rewrites or migrates the database itself.
#   On readiness failure it rolls back to the previous image automatically.
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
# shellcheck source=../common.sh
. "${DIST}/common.sh"

log() { printf '[update] %s\n' "$*" >&2; }
die() { printf '[update] ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "must run as root (sudo)."
NEW_TARBALL="${1:-}"
[ -n "${NEW_TARBALL}" ] && [ -f "${NEW_TARBALL}" ] || die "usage: update.sh NEW_TARBALL"
[ -d "${HS_OPT}" ] || die "no existing install at ${HS_OPT}; use install.sh for a fresh box."

PREV="${HS_OPT}.prev"
STAGING="$(mktemp -d)"; trap 'rm -rf "${STAGING}"' EXIT

# Read the event store without holding it open (best-effort; sqlite3 optional).
event_count() {
    command -v sqlite3 >/dev/null 2>&1 || { printf 'n/a'; return; }
    [ -f "${HS_DB_FILE}" ] || { printf '0'; return; }
    sqlite3 "file:${HS_DB_FILE}?mode=ro" 'SELECT COUNT(*) FROM events;' 2>/dev/null || printf 'n/a'
}
home_id() { [ -f "${HS_CONFIG_DIR}/home_id" ] && tr -d ' \r\n' < "${HS_CONFIG_DIR}/home_id" || printf ''; }

OLD_VERSION="$(cat "${HS_OPT}/VERSION" 2>/dev/null || echo unknown)"
PRE_COUNT="$(event_count)"; PRE_HOME="$(home_id)"
log "current=${OLD_VERSION}  events=${PRE_COUNT}  home_id=${PRE_HOME:-<none>}"

# ── 1. Stage + verify the new image ─────────────────────────────────────────
tar -C "${STAGING}" -xzf "${NEW_TARBALL}"
NEW_IMG="${STAGING}/opt/homesynapse"
[ -x "${NEW_IMG}/bin/homesynapse" ] || die "new tarball missing the launcher"
if [ -f "${NEW_IMG}/MANIFEST.sha256" ]; then
    ( cd "${NEW_IMG}" && sha256sum -c --quiet MANIFEST.sha256 ) || die "new image checksum failed"
fi
NEW_VERSION="$(cat "${NEW_IMG}/VERSION" 2>/dev/null || echo unknown)"
log "staged new image version=${NEW_VERSION}"

have_systemd() { [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; }
start_service() {
    if have_systemd; then
        systemctl start "${HS_UNIT}"
    else
        HOMESYNAPSE_HOME="${HS_HOME_ENV}" setpriv --reuid "${HS_USER}" --regid "${HS_GROUP}" \
            --clear-groups "${HS_LAUNCHER}" >/var/log/homesynapse-stdout.log 2>&1 &
        "${HS_OPT}/libexec/health-probe.sh" --wait --timeout 90 --health-path "${HS_HEALTH_PATH}"
    fi
}

# ── 2. Stop ─────────────────────────────────────────────────────────────────
log "stopping service (graceful SIGTERM, ≤30s grace) …"
if have_systemd; then systemctl stop "${HS_UNIT}" || true
else pkill -TERM -u "${HS_USER}" -f "${HS_LAUNCHER}" 2>/dev/null || true; sleep 3; fi

# ── 3. Swap image (data dir untouched) ──────────────────────────────────────
rm -rf "${PREV}"
mv "${HS_OPT}" "${PREV}"
mv "${NEW_IMG}" "${HS_OPT}"
log "image swapped ${OLD_VERSION} → ${NEW_VERSION} (rollback snapshot at ${PREV})"

# ── 4. Start + readiness, with auto-rollback ────────────────────────────────
if have_systemd; then systemctl daemon-reload || true; fi
if start_service && "${HS_OPT}/libexec/health-probe.sh" --wait --timeout 90 --health-path "${HS_HEALTH_PATH}"; then
    POST_COUNT="$(event_count)"; POST_HOME="$(home_id)"
    log "READY on ${NEW_VERSION}.  events: ${PRE_COUNT} → ${POST_COUNT}   home_id: ${POST_HOME:-<none>}"
    # Identity + store continuity guards (non-fatal warnings here; the smoke asserts hard).
    [ "${PRE_HOME}" = "${POST_HOME}" ] || log "WARNING: home_id changed across update!"
    log "update complete. Roll back manually with: mv ${PREV} ${HS_OPT} after a stop, if ever needed."
    exit 0
else
    log "new version did not become ready — ROLLING BACK to ${OLD_VERSION}"
    if have_systemd; then systemctl stop "${HS_UNIT}" || true
    else pkill -TERM -u "${HS_USER}" -f "${HS_LAUNCHER}" 2>/dev/null || true; sleep 3; fi
    rm -rf "${HS_OPT}"; mv "${PREV}" "${HS_OPT}"
    if have_systemd; then systemctl daemon-reload || true; fi
    start_service || true
    die "rolled back to ${OLD_VERSION}; investigate the new image before retrying."
fi
