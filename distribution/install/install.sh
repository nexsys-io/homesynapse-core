#!/usr/bin/env bash
#
# install.sh — dpkg-free installer for HomeSynapse Core.
#
#   sudo distribution/install/install.sh [TARBALL]
#
# Lays down the same layout as the .deb from a self-contained image tarball
# (distribution/image/build/homesynapse_<ver>_<arch>.tar.gz). For boxes without
# apt/dpkg, and the basis of a future `curl … | sh` flow. Fully offline: the
# tarball carries the JRE + every jar; nothing is downloaded here.
#
# Works with OR without systemd:
#   • systemd present → installs + enables + starts the unit (readiness-gated).
#   • no systemd      → launches the bundled launcher directly and probes
#                       (used by the minimal-container smoke).
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
# shellcheck source=../common.sh
. "${DIST}/common.sh"

log() { printf '[install] %s\n' "$*" >&2; }
die() { printf '[install] ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "must run as root (sudo)."

# ── Locate the image tarball ────────────────────────────────────────────────
TARBALL="${1:-}"
if [ -z "${TARBALL}" ]; then
    TARBALL="$(ls -1t "${DIST}"/image/build/homesynapse_*.tar.gz 2>/dev/null | head -1 || true)"
fi
[ -n "${TARBALL}" ] && [ -f "${TARBALL}" ] || die "image tarball not found; run distribution/image/build-image.sh first (or pass a path)."
log "installing from ${TARBALL}"

# ── 1. Lay down /opt/homesynapse atomically ─────────────────────────────────
STAGING="$(mktemp -d)"
trap 'rm -rf "${STAGING}"' EXIT
tar -C "${STAGING}" -xzf "${TARBALL}"            # → ${STAGING}/opt/homesynapse
[ -x "${STAGING}/opt/homesynapse/bin/homesynapse" ] || die "tarball missing the launcher"

# Verify the reproducibility manifest before we trust the bytes.
if [ -f "${STAGING}/opt/homesynapse/MANIFEST.sha256" ]; then
    ( cd "${STAGING}/opt/homesynapse" && sha256sum -c --quiet MANIFEST.sha256 ) \
        || die "image checksum verification failed"
    log "image checksums verified"
fi

mkdir -p "${HS_OPT%/*}"
if [ -d "${HS_OPT}" ]; then
    rm -rf "${HS_OPT}.old"; mv "${HS_OPT}" "${HS_OPT}.old"
fi
mv "${STAGING}/opt/homesynapse" "${HS_OPT}"
log "image → ${HS_OPT}"

# ── 2. Unit, env drop-in, CLI helpers ───────────────────────────────────────
install -D -m 0644 "${DIST}/systemd/homesynapse.service" "/lib/systemd/system/${HS_UNIT}"
if [ ! -e "${HS_ENV_FILE}" ]; then
    install -D -m 0644 "${DIST}/systemd/homesynapse.env.example" "${HS_ENV_FILE}"
fi
install -D -m 0755 "${DIST}/deb/homesynapse-token" "/usr/bin/homesynapse-token"
ln -sf "${HS_LAUNCHER}" /usr/bin/homesynapse

# ── 3. User + state tree (mirrors the .deb postinst) ────────────────────────
if ! getent group "${HS_GROUP}" >/dev/null; then addgroup --system "${HS_GROUP}"; fi
if ! getent passwd "${HS_USER}" >/dev/null; then
    adduser --system --ingroup "${HS_GROUP}" --home "${HS_DATA}" \
            --no-create-home --gecos "HomeSynapse Core" --disabled-login "${HS_USER}"
fi
install -d -o "${HS_USER}" -g "${HS_GROUP}" -m 0700 \
    "${HS_DATA}" "${HS_CONFIG_DIR}" "${HS_DATA}/data" "${HS_DATA}/backups" "${HS_DATA}/tmp"
install -d -o "${HS_USER}" -g "${HS_GROUP}" -m 0750 "${HS_LOG}"
install -d -o root        -g "${HS_GROUP}" -m 0750 "${HS_ETC}"

# ── 4. Start ────────────────────────────────────────────────────────────────
PROBE="${HS_OPT}/libexec/health-probe.sh"
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload || true
    systemctl enable "${HS_UNIT}" >/dev/null 2>&1 || true
    log "starting ${HS_UNIT} (readiness-gated by ExecStartPost) …"
    systemctl start "${HS_UNIT}" || die "service failed to start — journalctl -u ${HS_UNIT}"
else
    # No systemd (e.g. minimal container): run directly as the service user and probe.
    log "no systemd detected — launching directly as ${HS_USER}"
    install -d -o "${HS_USER}" -g "${HS_GROUP}" -m 0700 "${HS_DATA}"
    HOMESYNAPSE_HOME="${HS_HOME_ENV}" setpriv --reuid "${HS_USER}" --regid "${HS_GROUP}" \
        --clear-groups "${HS_LAUNCHER}" >/var/log/homesynapse-stdout.log 2>&1 &
    log "launched pid $!; probing readiness …"
    "${PROBE}" --wait --timeout 90 --health-path "${HS_HEALTH_PATH}" \
        || die "service did not become ready"
fi

# ── 5. Surface the pairing token path ───────────────────────────────────────
cat >&2 <<EOF
----------------------------------------------------------------
 HomeSynapse Core installed and running.
 First-run pairing token: ${HS_TOKEN_FILE}
   View it with:  sudo homesynapse-token
----------------------------------------------------------------
EOF
