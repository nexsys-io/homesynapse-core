#!/usr/bin/env bash
#
# version-grammar-test.sh — fixture-paired test for hs_version's bare-id wrapping
# (F-V1) and the version-of-record grammar build-image.sh asserts on its result.
#
#   distribution/smoke/version-grammar-test.sh                     exit 0 = every fixture passed
#   COMMON_SH=/path/to/common.sh distribution/smoke/version-grammar-test.sh
#                                                  drive another common.sh (the mutation check:
#                                                  the pre-F-V1 arm FAILS the two digit-leading
#                                                  bare-id rows; d26777c wrapped even then)
#
# What it proves, and how:
#   1. hs_version's `case` arm wraps EVERY non-tag-shaped `git describe` output as
#      0.1.0+g<id> and passes tag-shaped output through. HS_VERSION bypasses the arm
#      (common.sh returns it verbatim) and a VERSION file beside the caller bypasses
#      it too, so the fixtures drive the arm the only honest way: a stub `git` on
#      PATH answers `describe` with the fixture and `rev-parse` with 0, and hs_version
#      runs from a hermetic temp dir that carries no VERSION file, through the exact
#      `bash -c '. common.sh; hs_version'` invocation build-image.sh uses.
#   2. The grammar regex build-image.sh asserts accepts every wrapped/tag-shaped
#      fixture and REJECTS a bare id, a bare word, and the empty string (the
#      false-verdict boundary); the Debian-safe charset arm is mirrored likewise.
#   3. The regex literal is byte-identical in its three carriers — build-image.sh
#      and both install-smoke twins (copy, don't retype).
#
# Wired into the install-smoke "Static lint" step, so CI runs it on every push.
# passes-but-false input: a common.sh whose arm is right but whose VERSION-file or
# HS_VERSION branches regress — out of this test's charge by design (the rig
# deliberately disables both so the arm is what runs).
set -uo pipefail   # NOTE: not -e; run every fixture and report (the run-smoke idiom).

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
REPO="$(CDPATH= cd -- "${DIST}/.." && pwd)"
COMMON_SH="${COMMON_SH:-${DIST}/common.sh}"
GRAMMAR='^[0-9]+\.[0-9]+\.[0-9]+'   # build-image.sh's assert, verbatim (carrier-pinned below)

FAILS=0
log() { printf '[version-grammar-test] %s\n' "$*" >&2; }
ok()  { printf '[version-grammar-test] PASS  %s\n' "$*" >&2; }
bad() { printf '[version-grammar-test] FAIL  %s\n' "$*" >&2; FAILS=$((FAILS+1)); }

[ -f "${COMMON_SH}" ] || { log "common.sh not found at ${COMMON_SH}"; exit 2; }
log "common.sh=${COMMON_SH}"

# ── The rig: a stub git on PATH + a hermetic cwd with no VERSION file ───────
WORK="$(mktemp -d)"; trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/bin" "${WORK}/tree/cwd"
cat > "${WORK}/bin/git" <<'EOF'
#!/bin/sh
# stub git for version-grammar-test.sh — answers only what hs_version asks:
#   git -C <dir> rev-parse           -> exit 0 ("inside a repo")
#   git -C <dir> describe <flags...> -> the fixture in $FAKE_DESCRIBE
while [ $# -gt 0 ]; do
    case "$1" in
        -C) shift 2 ;;
        rev-parse) exit 0 ;;
        describe) printf '%s' "${FAKE_DESCRIBE-}"; exit 0 ;;
        *) shift ;;
    esac
done
exit 1
EOF
chmod 0755 "${WORK}/bin/git"

# hs_version exactly as build-image.sh runs it: `bash -c '. common.sh; hs_version'`.
# $0 is then `bash`, so _dist_dir() resolves to the cwd — the hermetic dir — and
# the VERSION lookups (cwd/VERSION, cwd/../VERSION) find nothing; HS_VERSION is
# empty (build-image passes HS_VERSION="${HS_VERSION:-}" the same way).
run_hs_version() {   # $1 = what the stub's `git describe` prints
    ( cd "${WORK}/tree/cwd" \
      && HS_VERSION= PATH="${WORK}/bin:${PATH}" FAKE_DESCRIBE="$1" \
         bash -c '. "'"${COMMON_SH}"'"; hs_version' )
}

# ── 1. The arm: bare ids wrap, tag-shaped passes through ────────────────────
# Expected values derived from the F-V1 exhibits: 7c9e4fa printed BARE on the
# 2026-08-22 Block-0 build; d26777c wrapped on the H3 artifact (the a-f asymmetry).
check_arm() {   # $1 = fake describe output, $2 = expected hs_version output
    got="$(run_hs_version "$1")"
    if [ "${got}" = "$2" ]; then ok "describe '$1' -> '${got}'"
    else bad "describe '$1' -> '${got}' (expected '$2')"; fi
}
check_arm '7c9e4fa'          '0.1.0+g7c9e4fa'
check_arm 'd26777c'          '0.1.0+gd26777c'
check_arm '7c9e4fa-dirty'    '0.1.0+g7c9e4fa-dirty'
check_arm '1.2.3'            '1.2.3'
check_arm '1.2.3-5-gabc1234' '1.2.3-5-gabc1234'
check_arm '1.2.3-dirty'      '1.2.3-dirty'
check_arm ''                 '0.1.0-skeleton'     # empty describe -> the fallback, unchanged

# ── 2. The grammar: accepts the wrapped/tag outputs, rejects the rest ───────
grammar_accepts() { printf '%s' "$1" | grep -Eq "${GRAMMAR}"; }
# build-image.sh's Debian-safe charset arm, mirrored byte-for-byte.
charset_accepts() { case "$1" in *[!0-9A-Za-z.+~-]*|'') return 1 ;; *) return 0 ;; esac; }
for v in '0.1.0+g7c9e4fa' '0.1.0+gd26777c' '0.1.0+g7c9e4fa-dirty' '1.2.3' '1.2.3-5-gabc1234' '1.2.3-dirty' '0.1.0-skeleton'; do
    if grammar_accepts "${v}" && charset_accepts "${v}"; then ok "grammar accepts '${v}'"
    else bad "grammar REJECTED '${v}' (must accept)"; fi
done
for v in '7c9e4fa' 'abc' ''; do
    if grammar_accepts "${v}"; then bad "grammar ACCEPTED '${v}' (must reject)"
    else ok "grammar rejects '${v}'"; fi
done
for v in '0.1.0 x' '0.1.0+g7c9e4fa:1' ''; do
    if charset_accepts "${v}"; then bad "charset ACCEPTED '${v}' (must reject)"
    else ok "charset rejects '${v}'"; fi
done

# ── 3. The regex literal is byte-identical in its three carriers ────────────
LITERAL="grep -Eq '${GRAMMAR}'"
for f in "${DIST}/image/build-image.sh" "${REPO}/.github/workflows/install-smoke.yml" "${DIST}/ci/install-smoke.yml"; do
    if [ -f "${f}" ] && grep -qF -- "${LITERAL}" "${f}"; then ok "regex literal present in ${f#"${REPO}/"}"
    else bad "regex literal ${LITERAL} missing in ${f#"${REPO}/"} (copy, don't retype)"; fi
done

# ╔══ Verdict ════════════════════════════════════════════════════════════════╗
echo "────────────────────────────────────────────────────────" >&2
if [ "${FAILS}" -eq 0 ]; then
    log "VERSION-GRAMMAR-TEST PASSED ✓"; exit 0
else
    log "VERSION-GRAMMAR-TEST FAILED ✗  (${FAILS} check(s) failed)"; exit 1
fi
