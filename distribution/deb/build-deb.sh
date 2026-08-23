#!/usr/bin/env bash
#
# build-deb.sh — assemble homesynapse_<ver>_<arch>.deb from the runtime image.
#
# Staging tree:
#   opt/homesynapse/...                     <- the jlinked image (from build-image.sh)
#   lib/systemd/system/homesynapse.service  <- the unit
#   etc/homesynapse/homesynapse.env         <- operator drop-in (conffile)
#   usr/bin/homesynapse                      <- symlink -> /opt/homesynapse/bin/homesynapse
#   usr/bin/homesynapse-token                <- token helper
#   DEBIAN/{control,conffiles,md5sums,postinst,prerm,postrm}
#
# Built with `dpkg-deb --root-owner-group` (no fakeroot needed on modern dpkg;
# falls back to fakeroot). Local-first: the package carries everything; nothing
# is fetched at install time.
set -euo pipefail
umask 022   # packaged dirs must be traversable (0755) and dpkg-deb needs DEBIAN >=0755

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
# shellcheck source=../common.sh
. "${DIST}/common.sh"

VERSION="$(HS_DIST_DIR="${DIST}" bash -c '. "'"${DIST}"'/common.sh"; hs_version')"
ARCH="$(hs_deb_arch)"
IMAGE_BUILD="${DIST}/image/build"
IMAGE_TREE="${IMAGE_BUILD}/opt/homesynapse"
OUT="${HERE}/build"
STAGE="${OUT}/stage"
DEB="${OUT}/homesynapse_${VERSION}_${ARCH}.deb"

log() { printf '[build-deb] %s\n' "$*" >&2; }
die() { printf '[build-deb] ERROR: %s\n' "$*" >&2; exit 1; }

# ── 0. Ensure the image exists ──────────────────────────────────────────────
if [ ! -d "${IMAGE_TREE}" ]; then
    log "image not built yet — running build-image.sh"
    "${DIST}/image/build-image.sh"
fi
[ -d "${IMAGE_TREE}" ] || die "image tree missing: ${IMAGE_TREE}"
[ -x "${IMAGE_TREE}/bin/homesynapse" ] || die "launcher missing in image"

# ── 1. Stage the filesystem tree ────────────────────────────────────────────
rm -rf "${OUT}"
mkdir -p "${STAGE}/opt" "${STAGE}/lib/systemd/system" \
         "${STAGE}/etc/homesynapse" "${STAGE}/usr/bin" "${STAGE}/DEBIAN"

cp -a "${IMAGE_TREE}" "${STAGE}/opt/homesynapse"
cp -p "${DIST}/systemd/homesynapse.service" "${STAGE}/lib/systemd/system/homesynapse.service"
cp -p "${DIST}/systemd/homesynapse.env.example" "${STAGE}/etc/homesynapse/homesynapse.env"
cp -p "${HERE}/homesynapse-token" "${STAGE}/usr/bin/homesynapse-token"
chmod 0755 "${STAGE}/usr/bin/homesynapse-token"
# CLI symlink (absolute target; valid once installed under /).
ln -sf /opt/homesynapse/bin/homesynapse "${STAGE}/usr/bin/homesynapse"

# ── 2. DEBIAN metadata ──────────────────────────────────────────────────────
INSTALLED_SIZE="$(du -sk "${STAGE}/opt" "${STAGE}/usr" "${STAGE}/lib" "${STAGE}/etc" \
                    | awk '{s+=$1} END{print s}')"
sed -e "s/@VERSION@/${VERSION}/" \
    -e "s/@ARCH@/${ARCH}/" \
    -e "s/@INSTALLED_SIZE@/${INSTALLED_SIZE}/" \
    "${HERE}/debian/control.in" > "${STAGE}/DEBIAN/control"

cp -p "${HERE}/debian/conffiles" "${STAGE}/DEBIAN/conffiles"
for s in postinst prerm postrm; do
    cp -p "${HERE}/debian/${s}" "${STAGE}/DEBIAN/${s}"
    chmod 0755 "${STAGE}/DEBIAN/${s}"
done

# Normalize perms: dirs traversable, every payload file readable by the service
# user (the homesynapse user must read the jars + runtime), exec bits preserved
# (jlink/cp -a already set them on bin/runtime; we only ADD read, never strip).
find "${STAGE}" -type d -exec chmod 0755 {} +
find "${STAGE}" -type f -exec chmod a+r {} +
# Data files that must NOT be executable (the unit + conffile), pinned regardless
# of source perms.
chmod 0644 "${STAGE}/lib/systemd/system/homesynapse.service" \
           "${STAGE}/etc/homesynapse/homesynapse.env"
chmod 0755 "${STAGE}/DEBIAN" "${STAGE}/DEBIAN/postinst" "${STAGE}/DEBIAN/prerm" "${STAGE}/DEBIAN/postrm"
chmod 0644 "${STAGE}/DEBIAN/control" "${STAGE}/DEBIAN/conffiles"

# md5sums for every shipped file (dpkg verification + apt integrity).
( cd "${STAGE}" && find . -type f -not -path './DEBIAN/*' -printf '%P\0' \
    | sort -z | xargs -0 md5sum > DEBIAN/md5sums )
chmod 0644 "${STAGE}/DEBIAN/md5sums"

# ── 3. Build ────────────────────────────────────────────────────────────────
log "building ${DEB} (Installed-Size=${INSTALLED_SIZE} KB)"
if dpkg-deb --help 2>&1 | grep -q -- '--root-owner-group'; then
    dpkg-deb --root-owner-group --build "${STAGE}" "${DEB}"
elif command -v fakeroot >/dev/null 2>&1; then
    fakeroot dpkg-deb --build "${STAGE}" "${DEB}"
else
    die "need dpkg-deb with --root-owner-group, or fakeroot"
fi

# ── 4. Validate ─────────────────────────────────────────────────────────────
log "-- dpkg-deb --info --"
dpkg-deb --info "${DEB}" >&2
log "-- dpkg-deb --contents (head) --"
# `| head` closes the pipe after 40 lines; under `set -euo pipefail` dpkg-deb then
# errors on the broken pipe (exit 2) and fails the step on a *diagnostic* print
# (the .deb was already built above). Never let this informational dump fail the build.
dpkg-deb --contents "${DEB}" 2>/dev/null | head -40 >&2 || true
if command -v lintian >/dev/null 2>&1; then
    log "-- lintian (informational; failures non-fatal for the skeleton) --"
    lintian "${DEB}" >&2 || true
fi

log "DONE -> ${DEB}"
printf '%s\n' "${DEB}"
