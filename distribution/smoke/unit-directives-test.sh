#!/usr/bin/env bash
#
# unit-directives-test.sh — fixture-paired lint for the seven load-bearing directives of
# distribution/systemd/homesynapse.service (FAILCHAN, 2026-09-04 — R-10 Row 6 (a) + EXITCODE (a)).
#
#   distribution/smoke/unit-directives-test.sh               exit 0 = every directive present exactly once
#   distribution/smoke/unit-directives-test.sh <unit-path>   drive another unit (the mutation check: the
#                                                            ef02d13 unit FAILS exactly 2 rows — no
#                                                            SuccessExitStatus=143; Restart=on-failure)
#
# What it proves, and how: each required line must appear EXACTLY ONCE as an ACTIVE, whole-line
# directive (`grep -c -x -F`), so the commented DANGER-block shape (`#   Type=notify`,
# `#   Restart=on-watchdog`) never counts and a duplicated directive — systemd honours the LAST
# assignment, silently — is a miss too. `systemd-analyze verify` (the step before this one)
# proves the unit PARSES; this proves the unit SAYS what the boot contract needs: Type=exec ·
# the SIGTERM stop · 143-is-clean · Restart=always · the deterministic-config no-loop (10) ·
# the start limit · the unauthenticated /health readiness gate.
# passes-but-false input: a directive supplied by a drop-in override instead of the unit —
# outside this lint's charge by design (the unit file is the artifact CI ships).
# Wired into the install-smoke "Static lint" step after the systemd-analyze block.
# bash -n AND sh -n (dash) clean — POSIX sh constructs only.
set -u   # NOTE: not -e; run every row and report (the run-smoke idiom).

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
UNIT="${1:-${HERE}/../systemd/homesynapse.service}"

log() { printf '[unit-directives-test] %s\n' "$*" >&2; }
[ -f "${UNIT}" ] || { log "unit file not found at ${UNIT}"; exit 2; }
log "unit=${UNIT}"

CHECKED=0
MISSES=0
check() {   # $1 = the exact active directive line, byte-for-byte
    CHECKED=$((CHECKED+1))
    # grep -c prints the count even when it exits 1 on zero matches (no -e in force).
    n="$(grep -c -x -F -e "$1" "${UNIT}")"
    case "${n}" in
        1) printf '[unit-directives-test] PASS  %s\n' "$1" >&2 ;;
        0) printf '[unit-directives-test] FAIL  %s (missing as an active directive)\n' "$1" >&2
           MISSES=$((MISSES+1)) ;;
        *) printf '[unit-directives-test] FAIL  %s (present %s times; exactly once required)\n' "$1" "${n}" >&2
           MISSES=$((MISSES+1)) ;;
    esac
}

check 'Type=exec'
check 'KillSignal=SIGTERM'
check 'SuccessExitStatus=143'
check 'Restart=always'
check 'RestartPreventExitStatus=10'
check 'StartLimitBurst=5'
check 'ExecStartPost=/opt/homesynapse/libexec/health-probe.sh --wait --timeout 90 --health-path /health'

# ╔══ Verdict ════════════════════════════════════════════════════════════════╗
echo "────────────────────────────────────────────────────────" >&2
if [ "${MISSES}" -eq 0 ]; then
    log "PASSED ✓ (${CHECKED} directives)"; exit 0
else
    log "FAILED ✗ (${MISSES} of ${CHECKED} directives missing or duplicated)"; exit 1
fi
