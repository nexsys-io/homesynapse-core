#!/usr/bin/env bash
#
# version-grammar-test.sh — fixture-paired test for hs_version's version scheme
# (R-V: 0.1.0+git<YYYYMMDD.HHMMSS>.g<sha>, post-F-V1), its resolution ORDER, and
# the version-of-record grammar build-image.sh asserts on its result.
#
#   distribution/smoke/version-grammar-test.sh                     exit 0 = every fixture passed
#   COMMON_SH=/path/to/common.sh distribution/smoke/version-grammar-test.sh
#                                                  drive another common.sh (the mutation check:
#                                                  the pre-R-7b common.sh FAILS exactly 5 rows —
#                                                  the three +git arm rows, the empty-date row,
#                                                  and the VERSION-file-bypass row)
#
# Checks: 34 with dpkg on the host (10 arm + 9 accept + 3 grammar-reject + 3 charset-reject
#         + 4 ordering + 5 carriers); 30 plus ONE explicit SKIP line without dpkg.
#
# What it proves, and how:
#   1. hs_version's `case` arm wraps EVERY non-tag-shaped `git describe` output as
#      0.1.0+git<committer-date UTC>.g<id> (a -dirty suffix rides through), passes
#      tag-shaped output through, and prints '' (fail-closed) when the committer date
#      is missing or malformed. The resolution ORDER is git → VERSION file: a VERSION
#      file beside the caller no longer outranks the commit (the cwd accident of R-7
#      pushback item 8), and it still carries the version when git is absent.
#      HS_VERSION bypasses everything (common.sh returns it verbatim), so the fixtures
#      drive the arm the only honest way: a stub `git` on PATH answers `describe`,
#      `log -1` and `rev-parse` from fixtures (FAKE_DESCRIBE / FAKE_CDATE / FAKE_NOGIT),
#      and hs_version runs from a hermetic temp dir through the exact
#      `HS_DIST_DIR=… bash -c '. common.sh; hs_version'` invocation the build scripts
#      use. HS_DIST_DIR is set to the hermetic cwd ITSELF, so the pre-R-7b _dist_dir
#      (which ignores it and resolves the cwd) lands on the same dir: a mutation run
#      isolates the ORDER change, never the dir change.
#   2. The grammar regex build-image.sh asserts accepts every wrapped/tag-shaped
#      fixture and REJECTS a bare id, a bare word, and the empty string (the
#      false-verdict boundary); the Debian-safe charset arm is mirrored likewise.
#   2b. The ORDERING argument of R-V, asserted at dpkg itself when present: a +git
#      build sorts above every legacy +g<sha> build (g is a proper prefix of git),
#      time decides between two +git builds (never the sha), and a bare id still
#      sorts above everything (the documented one-time --allow-downgrades).
#   3. The regex literal is byte-identical in its three carriers — build-image.sh
#      and both install-smoke twins (copy, don't retype) — and both twins carry the
#      0.1.0-skeleton fence (the git-less fallback passes the grammar; in CI it is
#      never lawful, so the echo step must refuse it).
#
# Wired into the install-smoke "Static lint" step, so CI runs it on every push.
# passes-but-false input: a common.sh whose arm and order are right but whose
# HS_VERSION branch regresses — out of this test's charge by design (the rig
# deliberately disables it so the arm is what runs).
set -uo pipefail   # NOTE: not -e; run every fixture and report (the run-smoke idiom).

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$(CDPATH= cd -- "${HERE}/.." && pwd)"
REPO="$(CDPATH= cd -- "${DIST}/.." && pwd)"
COMMON_SH="${COMMON_SH:-${DIST}/common.sh}"
GRAMMAR='^[0-9]+\.[0-9]+\.[0-9]+'   # build-image.sh's assert, verbatim (carrier-pinned below)

PASSES=0
FAILS=0
log() { printf '[version-grammar-test] %s\n' "$*" >&2; }
ok()  { printf '[version-grammar-test] PASS  %s\n' "$*" >&2; PASSES=$((PASSES+1)); }
bad() { printf '[version-grammar-test] FAIL  %s\n' "$*" >&2; FAILS=$((FAILS+1)); }

[ -f "${COMMON_SH}" ] || { log "common.sh not found at ${COMMON_SH}"; exit 2; }
log "common.sh=${COMMON_SH}"

# ── The rig: a stub git on PATH + a hermetic cwd with no VERSION file ───────
WORK="$(mktemp -d)"; trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/bin" "${WORK}/tree/cwd"
cat > "${WORK}/bin/git" <<'EOF'
#!/bin/sh
# stub git for version-grammar-test.sh — answers only what hs_version asks:
#   git -C <dir> rev-parse           -> exit 0 ("inside a repo"); exit 1 under FAKE_NOGIT=1
#   git -C <dir> describe <flags...> -> the fixture in $FAKE_DESCRIBE (nothing under FAKE_NOGIT=1)
#   git -C <dir> log -1 <flags...>   -> the fixture in $FAKE_CDATE (the committer date)
while [ $# -gt 0 ]; do
    case "$1" in
        -C) shift 2 ;;
        rev-parse) [ -z "${FAKE_NOGIT-}" ] || exit 1; exit 0 ;;
        describe) [ -z "${FAKE_NOGIT-}" ] || exit 0; printf '%s' "${FAKE_DESCRIBE-}"; exit 0 ;;
        log) printf '%s' "${FAKE_CDATE-}"; exit 0 ;;
        *) shift ;;
    esac
done
exit 1
EOF
chmod 0755 "${WORK}/bin/git"

# hs_version exactly as the build scripts run it: `HS_DIST_DIR=<dist> bash -c '. common.sh; hs_version'`.
# $0 is then `bash`, so without HS_DIST_DIR _dist_dir() would resolve to the cwd; the
# rig passes the hermetic cwd as HS_DIST_DIR so old and new _dist_dir agree on the dir.
# HS_VERSION is empty (build-image passes HS_VERSION="${HS_VERSION:-}" the same way).
run_hs_version() {   # $1 = what the stub's `git describe` prints, $2 = what its `git log -1` prints
    ( cd "${WORK}/tree/cwd" \
      && HS_VERSION= HS_DIST_DIR="${WORK}/tree/cwd" PATH="${WORK}/bin:${PATH}" \
         FAKE_DESCRIBE="$1" FAKE_CDATE="$2" FAKE_NOGIT="${FAKE_NOGIT-}" \
         bash -c '. "'"${COMMON_SH}"'"; hs_version' )
}
plant_version_file()   { printf '%s\n' "$1" > "${WORK}/tree/cwd/VERSION"; }
unplant_version_file() { rm -f "${WORK}/tree/cwd/VERSION"; }

# ── 1. The arm + the order ──────────────────────────────────────────────────
# Expected values derived from the F-V1 exhibits (7c9e4fa printed BARE on the
# 2026-08-22 Block-0 build; d26777c wrapped on the H3 artifact) under the R-V scheme.
CDATE='20260822.143100'   # the committer date, UTC, as `git log -1 --date=format-local:%Y%m%d.%H%M%S` prints it
check_arm() {   # $1 = fake describe, $2 = fake committer date, $3 = expected hs_version, $4 = optional rig note
    got="$(run_hs_version "$1" "$2")"
    if [ "${got}" = "$3" ]; then ok "describe '$1' cdate '$2' -> '${got}' ${4-}"
    else bad "describe '$1' cdate '$2' -> '${got}' (expected '$3') ${4-}"; fi
}
check_arm '7c9e4fa'          "${CDATE}" '0.1.0+git20260822.143100.g7c9e4fa'
check_arm 'd26777c'          "${CDATE}" '0.1.0+git20260822.143100.gd26777c'
check_arm '7c9e4fa-dirty'    "${CDATE}" '0.1.0+git20260822.143100.g7c9e4fa-dirty'
check_arm '1.2.3'            "${CDATE}" '1.2.3'
check_arm '1.2.3-5-gabc1234' "${CDATE}" '1.2.3-5-gabc1234'
check_arm '1.2.3-dirty'      "${CDATE}" '1.2.3-dirty'
check_arm ''                 "${CDATE}" '0.1.0-skeleton'     # empty describe -> the fallback, unchanged
check_arm '7c9e4fa'          ''         ''                   # no committer date -> '' (fail-closed; never 0.1.0+g<sha>)
# The order: git outranks a VERSION file beside the caller (the H-1 cwd bypass closed at the function) …
plant_version_file '0.1.0-skeleton'
check_arm '7c9e4fa'          "${CDATE}" '0.1.0+git20260822.143100.g7c9e4fa' '[cwd/VERSION=0.1.0-skeleton planted]'
unplant_version_file
# … and the VERSION file still carries the version when there is no git (the tarball-export path).
plant_version_file '1.2.3'
FAKE_NOGIT=1 check_arm ''    ''         '1.2.3'              '[FAKE_NOGIT=1, cwd/VERSION=1.2.3 planted]'
unplant_version_file

# ── 2. The grammar: accepts the wrapped/tag outputs, rejects the rest ───────
grammar_accepts() { printf '%s' "$1" | grep -Eq "${GRAMMAR}"; }
# build-image.sh's Debian-safe charset arm, mirrored byte-for-byte.
charset_accepts() { case "$1" in *[!0-9A-Za-z.+~-]*|'') return 1 ;; *) return 0 ;; esac; }
for v in '0.1.0+git20260822.143100.g7c9e4fa' '0.1.0+git20260822.143100.g7c9e4fa-dirty' \
         '0.1.0+g7c9e4fa' '0.1.0+gd26777c' '0.1.0+g7c9e4fa-dirty' '1.2.3' '1.2.3-5-gabc1234' '1.2.3-dirty' '0.1.0-skeleton'; do
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

# ── 2b. The ordering (the proof of R-V), at dpkg itself when the host has it ─
ORDERING_ROWS=0
if command -v dpkg >/dev/null 2>&1; then
    ORDERING_ROWS=4
    check_order() {   # $1 gt $2 per dpkg's version comparison (a bare id warns on stderr; the verdict is the exit status)
        if dpkg --compare-versions "$1" gt "$2" 2>/dev/null; then ok "dpkg orders '$1' gt '$2'"
        else bad "dpkg does NOT order '$1' gt '$2'"; fi
    }
    check_order '0.1.0+git20260822.143100.g7c9e4fa'  '0.1.0+g7c9e4fa'                       # +git… above the legacy +g… (g is a proper prefix of git)
    check_order '0.1.0+git20260822.143100.g7c9e4fa'  '0.1.0+gd26777c'                       # … whatever the legacy sha
    check_order '0.1.0+git20260822.143100.g1111111'  '0.1.0+git20260822.143059.gffffffff'   # time decides, not the sha
    check_order '7c9e4fa'                            '0.1.0+git20260822.143100.g7c9e4fa'   # a bare id still sorts above everything: the documented one-time --allow-downgrades
else
    log "SKIP  ordering rows (no dpkg on this host)"
fi

# ── 3. The carriers: the regex literal byte-identical in three, the skeleton fence in both twins ─
LITERAL="grep -Eq '${GRAMMAR}'"
for f in "${DIST}/image/build-image.sh" "${REPO}/.github/workflows/install-smoke.yml" "${DIST}/ci/install-smoke.yml"; do
    if [ -f "${f}" ] && grep -qF -- "${LITERAL}" "${f}"; then ok "regex literal present in ${f#"${REPO}/"}"
    else bad "regex literal ${LITERAL} missing in ${f#"${REPO}/"} (copy, don't retype)"; fi
done
FENCE='!= "0.1.0-skeleton"'
for f in "${REPO}/.github/workflows/install-smoke.yml" "${DIST}/ci/install-smoke.yml"; do
    if [ -f "${f}" ] && grep -qF -- "${FENCE}" "${f}"; then ok "skeleton fence ${FENCE} present in ${f#"${REPO}/"}"
    else bad "skeleton fence ${FENCE} missing in ${f#"${REPO}/"}"; fi
done

# ╔══ Verdict ════════════════════════════════════════════════════════════════╗
EXPECTED=$((30 + ORDERING_ROWS))
RAN=$((PASSES + FAILS))
[ "${RAN}" -eq "${EXPECTED}" ] || bad "check count ${RAN} != expected ${EXPECTED} (a row did not run)"
echo "────────────────────────────────────────────────────────" >&2
if [ "${FAILS}" -eq 0 ]; then
    log "VERSION-GRAMMAR-TEST PASSED ✓  (${PASSES} checks)"; exit 0
else
    log "VERSION-GRAMMAR-TEST FAILED ✗  (${FAILS} of ${RAN} check(s) failed)"; exit 1
fi
