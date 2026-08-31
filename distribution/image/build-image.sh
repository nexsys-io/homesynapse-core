#!/usr/bin/env bash
#
# build-image.sh — produce the self-contained HomeSynapse Core runtime image.
#
#   Gradle :app:homesynapse-app:installDist   (compile + resolve the full jar set)
#        → jlink a minimal JDK-module runtime  (custom JRE, no system Java needed)
#        → lay app + dependency jars on top    (run on the classpath under that JRE)
#        → write the /opt/homesynapse/bin/homesynapse launcher (LTD-01 JVM flags)
#        → stamp VERSION + MANIFEST.sha256      (reproducibility / verify)
#
# WHY classpath-under-a-jlinked-JRE (not a full modular jlink image):
#   The first-party modules are all explicit JPMS modules, but the third-party
#   dependency set (Javalin/Jetty, Jackson, sqlite-jdbc, logback, …) mixes
#   explicit and *automatic* modules. jlink refuses to LINK automatic modules.
#   So we jlink only the JDK platform modules into a tiny runtime, then run the
#   whole app on the classpath inside it. This is bulletproof, offline, and
#   sidesteps every module-path split-package landmine. Folding the link step
#   into Gradle (badass-jlink in application-conventions) is escalation E5 —
#   it needs a build-logic edit, which is outside this lane's write-isolation.
#
# Output: distribution/image/build/opt/homesynapse/   (the installable image tree)
#         distribution/image/build/homesynapse_<ver>_<arch>.tar.gz (packaged tarball)
#
# Reproducible: pin JDK 21; deterministic jlink flags; sorted checksum manifest.
# Offline at INSTALL time (the image is self-contained); the BUILD resolves from
# Maven Central / the JDK once, which is expected and fine.
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
REPO="$(CDPATH= cd -- "${DIST}/.." && pwd)"
# shellcheck source=../common.sh
. "${DIST}/common.sh"

VERSION="$(HS_VERSION="${HS_VERSION:-}" HS_DIST_DIR="${DIST}" bash -c '. "'"${DIST}"'/common.sh"; hs_version')"
ARCH="$(hs_deb_arch)"
OUT="${HERE}/build"
IMAGE="${OUT}${HS_OPT}"          # build/opt/homesynapse — staged at its install path
APP_MODULE="com.homesynapse.app"
MAIN_CLASS="com.homesynapse.app.Main"
GRADLE_TASK=":app:homesynapse-app:installDist"

log() { printf '[build-image] %s\n' "$*" >&2; }
die() { printf '[build-image] ERROR: %s\n' "$*" >&2; exit 1; }

# ── Version-of-record grammar assert (F-V1) ─────────────────────────────────
# hs_version (common.sh) wraps every bare commit id as 0.1.0+git<date>.g<id>; a bare
# id that leaks through (7c9e4fa — the 2026-08-22 Block-0 build) sorts ABOVE every
# 0.x.y in dpkg, so every later wrapped build becomes an apt "downgrade". Asserted
# HERE, outside any $( ) (a die inside a substitution cannot abort the script) and
# before the version is used anywhere; the install-smoke workflow echoes the
# same regex after the .deb is assembled — one instrument, two rigs.
# passes-but-false input: the git-less fallback 0.1.0-skeleton (distribution/VERSION,
# reached only when git is absent or the tree is not a repository) is tag-shaped
# and Debian-safe, so it passes both checks below. In CI it is never lawful
# (actions/checkout leaves .git, so the commit decides) and the twins' echo step
# fences it ([ "${V}" != "0.1.0-skeleton" ]). A tag named like a version but
# semantically wrong is a hypothetical — the repository carries no tags.
case "${VERSION}" in
    *[!0-9A-Za-z.+~-]*|'') die "version of record '${VERSION}' is not a Debian-safe version string" ;;
esac
printf '%s' "${VERSION}" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+' \
    || die "version of record '${VERSION}' is not tag-shaped (expected ^[0-9]+\.[0-9]+\.[0-9]+ — hs_version must wrap bare ids as 0.1.0+git<date>.g<id>)"

# ── 0. Toolchain preflight ──────────────────────────────────────────────────
JAVA_HOME="${JAVA_HOME:-}"
[ -n "${JAVA_HOME}" ] || die "JAVA_HOME must point at a JDK 21 (Corretto in CI)."
JLINK="${JAVA_HOME}/bin/jlink"
JDEPS="${JAVA_HOME}/bin/jdeps"
JAVA="${JAVA_HOME}/bin/java"
for t in "${JLINK}" "${JDEPS}" "${JAVA}"; do
    [ -x "${t}" ] || die "missing JDK tool: ${t}"
done
JFEATURE="$("${JAVA}" -version 2>&1 | awk -F'"' '/version/{split($2,a,"."); print (a[1]=="1"?a[2]:a[1])}')"
[ "${JFEATURE}" = "21" ] || log "WARNING: JDK feature version is ${JFEATURE}, expected 21 (reproducibility/LTD-01)."

log "version=${VERSION} arch=${ARCH} jdk=${JFEATURE}"
rm -rf "${OUT}"
mkdir -p "${IMAGE}/bin" "${IMAGE}/lib"

# ── 1. Gradle installDist — compile + resolve the runtime jar set ───────────
log "running Gradle ${GRADLE_TASK} …"
( cd "${REPO}" && ./gradlew --no-daemon "${GRADLE_TASK}" )
INSTALL_LIB="${REPO}/app/homesynapse-app/build/install/homesynapse-app/lib"
[ -d "${INSTALL_LIB}" ] || die "installDist lib dir not found: ${INSTALL_LIB}"

# Copy the dependency closure (sorted for a deterministic manifest).
find "${INSTALL_LIB}" -maxdepth 1 -name '*.jar' -print0 \
    | sort -z | xargs -0 -I{} cp -p {} "${IMAGE}/lib/"
JAR_COUNT="$(find "${IMAGE}/lib" -name '*.jar' | wc -l | tr -d ' ')"
log "bundled ${JAR_COUNT} jars"

# ── 2. jlink a minimal JDK-module runtime ───────────────────────────────────
# Determine the JDK modules the app actually needs. EVERY bundled jar is a
# jdeps root, so the closure covers the whole classpath (F-23: the previous
# single-root invocation analysed only the app jar — --class-path is where
# jdeps RESOLVES classes, not what it ANALYSES — so a JDK module used solely
# by another first-party jar, e.g. jdk.jfr in event-bus, was invisible). We
# union with a known-good floor so the image never under-links (some modules
# are reached reflectively and jdeps cannot see them).
# jdeps emits 'Warning: split package:' lines on STDOUT under this all-roots
# shape (each jar is both a root and a --class-path member) — the grep -Ex
# whitelists the pure comma-joined module-deps line; nothing else can enter.
log "computing JDK module set via jdeps over the full jar set …"
JDEPS_MODS="$(
    find "${IMAGE}/lib" -maxdepth 1 -name '*.jar' -print0 | sort -z \
        | xargs -0 "${JDEPS}" --print-module-deps --ignore-missing-deps \
            --multi-release "${JFEATURE}" --class-path "${IMAGE}/lib/*" \
        2>/dev/null | grep -Ex '[a-zA-Z0-9._]+(,[a-zA-Z0-9._]+)*' || true
)"
# Floor: modules commonly reached via reflection/service loading that jdeps may
# miss. jdk.jfr rides the floor too (belt AND suspenders with the full-jar-set
# jdeps above) — JFR event registration is exactly the reflective-reach class.
FLOOR="java.base,java.logging,java.naming,java.sql,java.management,java.xml,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.jfr"
ADD_MODULES="$(printf '%s,%s' "${JDEPS_MODS:-java.base}" "${FLOOR}" \
    | tr ',' '\n' | sed '/^$/d' | sort -u | paste -sd, -)"
log "jlink --add-modules ${ADD_MODULES}"

"${JLINK}" \
    --add-modules "${ADD_MODULES}" \
    --strip-debug --no-header-files --no-man-pages \
    --compress=zip-6 \
    --dedup-legal-notices=error-if-not-same-content \
    --output "${IMAGE}/runtime"
log "jlinked runtime → $(du -sh "${IMAGE}/runtime" | cut -f1)"

# ── 2b. Floor-presence assert — every requested module IS in the runtime ────
# passes-but-false input: a module listed but broken at link — bounded by
# jlink's own --dedup-legal-notices=error-if-not-same-content and the R-1
# runtime probe downstream.
LINKED="$("${IMAGE}/runtime/bin/java" --list-modules | cut -d@ -f1)" \
    || die "runtime --list-modules failed — the jlinked image is broken"
MISSING=""
for m in $(printf '%s' "${ADD_MODULES}" | tr ',' ' '); do
    printf '%s\n' "${LINKED}" | grep -qxF "${m}" || MISSING="${MISSING} ${m}"
done
[ -z "${MISSING}" ] || die "jlinked runtime is MISSING requested module(s):${MISSING}"
log "floor-presence assert green: all $(printf '%s\n' "${ADD_MODULES}" | tr ',' '\n' | grep -c .) requested modules present in the runtime"

# ── 3. Launcher — the Doc 12 / LTD-01 contract ──────────────────────────────
# Applies the Locked JVM flags, uses the bundled JRE, runs the app on the
# classpath. HOMESYNAPSE_JVM_OPTS lets operators append flags without editing
# the image (the systemd EnvironmentFile / env drop-in feeds it through).
cat > "${IMAGE}/bin/homesynapse" <<EOF
#!/bin/sh
# HomeSynapse Core launcher (generated by distribution/image/build-image.sh).
# Doc 12 §3.2 / LTD-01 — applies the Locked JVM flags and the bundled runtime.
set -eu
HS_HOME_DIR="\$(CDPATH= cd -- "\$(dirname -- "\$0")/.." && pwd)"
# Optional AppCDS (Doc 12 §3.2 "AppCDS enabled when the archive exists"):
HS_CDS=""
if [ -n "\${HOMESYNAPSE_CDS_ARCHIVE:-}" ]; then
    HS_CDS="-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=\${HOMESYNAPSE_CDS_ARCHIVE}"
fi
exec "\${HS_HOME_DIR}/runtime/bin/java" \\
    ${HS_JVM_FLAGS} \\
    \${HS_CDS} \\
    \${HOMESYNAPSE_JVM_OPTS:-} \\
    -cp "\${HS_HOME_DIR}/lib/*" \\
    ${MAIN_CLASS} "\$@"
EOF
chmod 0755 "${IMAGE}/bin/homesynapse"

# ── 3b. Bundle the runtime health probe (the unit's ExecStartPost needs it) ──
# Shipped inside the image so it exists at a stable path where the distribution/
# tree is absent: /opt/homesynapse/libexec/health-probe.sh.
mkdir -p "${IMAGE}/libexec"
cp -p "${DIST}/smoke/health-probe.sh" "${IMAGE}/libexec/health-probe.sh"
chmod 0755 "${IMAGE}/libexec/health-probe.sh"

# ── 4. Stamp version + reproducibility manifest ─────────────────────────────
printf '%s\n' "${VERSION}" > "${IMAGE}/VERSION"
( cd "${IMAGE}" && find runtime lib bin libexec VERSION -type f -print0 \
    | sort -z | xargs -0 sha256sum > MANIFEST.sha256 )
log "wrote MANIFEST.sha256 ($(wc -l < "${IMAGE}/MANIFEST.sha256" | tr -d ' ') entries)"

# ── 5. Package a self-contained tarball (consumed by install.sh / update.sh) ─
TARBALL="${OUT}/homesynapse_${VERSION}_${ARCH}.tar.gz"
# Deterministic tar: sorted names, fixed owner/mtime → reproducible bytes.
( cd "${OUT}" && tar \
    --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime="@${SOURCE_DATE_EPOCH:-0}" \
    -czf "${TARBALL}" "opt" )
log "image tarball → ${TARBALL}"

cat >&2 <<EOF
[build-image] DONE
  image tree : ${IMAGE}
  tarball    : ${TARBALL}
  launcher   : ${HS_LAUNCHER}  (installed path)
  version    : ${VERSION}   arch: ${ARCH}
Next: distribution/deb/build-deb.sh   or   distribution/install/install.sh ${TARBALL}
EOF
