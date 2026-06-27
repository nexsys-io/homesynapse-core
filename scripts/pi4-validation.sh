#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi4-validation.sh — Full-hour on-device M3.4b integration test runner.
#
# Deploys the current working tree to hs-dev-1, runs the Pi-profile
# integration test suite end-to-end (BurstLoadIT, HeapBudgetIT,
# Pi4SustainedLoadIT, Pi4D1SpikeIT, CrashRecoveryIT), and pulls the JUnit
# reports + JFR recordings back to a local timestamped directory.
#
# Usage:
#   ./scripts/pi4-validation.sh                # SUSTAINED_MINUTES=60
#   ./scripts/pi4-validation.sh 10             # SUSTAINED_MINUTES=10 (CI/dev)
#   ./scripts/pi4-validation.sh --dry-run      # validate config, do nothing
#   ./scripts/pi4-validation.sh -h | --help    # show this banner

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (override via environment)
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-hs-dev-1}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-/opt/homesynapse-tests}"
DEFAULT_SUSTAINED_MINUTES=60

# ---------------------------------------------------------------------------
# Colors
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' BOLD='' NC=''
fi

info()   { printf "${BLUE}[INFO]${NC}  %s\n" "$1"; }
ok()     { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn()   { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail()   { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }
header() { printf "\n${BOLD}=== %s ===${NC}\n" "$1"; }

usage() {
    cat <<EOF
pi4-validation.sh — M3.4b integration test runner against ${PI_HOST}.

Usage:
  $(basename "$0") [SUSTAINED_MINUTES]
  $(basename "$0") --dry-run
  $(basename "$0") -h | --help

Arguments:
  SUSTAINED_MINUTES   Pi4SustainedLoadIT duration, in minutes
                      (default: ${DEFAULT_SUSTAINED_MINUTES})

Options:
  --dry-run           Print the planned actions and exit without running them.
  -h, --help          Show this banner.

Environment:
  PI_HOST             SSH alias for the Pi (default: hs-dev-1)
  PI_PROJECT_DIR      Remote project root (default: /opt/homesynapse-tests)

Outputs:
  pi4-validation-YYYYMMDDTHHMMSSZ/
      gradle.log          combined stdout + stderr from the remote gradle run
      test-results/       JUnit XML
      reports/            HTML report + any JFR recordings
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
DRY_RUN=false
SUSTAINED_MINUTES="${DEFAULT_SUSTAINED_MINUTES}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage ;;
        --dry-run) DRY_RUN=true; shift ;;
        *)
            if [[ "$1" =~ ^[0-9]+$ ]]; then
                SUSTAINED_MINUTES="$1"
                shift
            else
                fail "Unrecognized argument: $1"
                printf "\n"
                usage
            fi
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOCAL_RESULTS_DIR="$(pwd)/pi4-validation-${TIMESTAMP}"
REMOTE_RESULTS_PATH="${PI_PROJECT_DIR}/testing/integration-tests/build"
GRADLE_CMD="./gradlew :testing:integration-tests:test -PpiProfile=throttled -PsustainedMinutes=${SUSTAINED_MINUTES}"

header "M3.4b Pi-4 Validation"
info "Remote host:           ${PI_HOST}"
info "Remote project dir:    ${PI_PROJECT_DIR}"
info "Sustained minutes:     ${SUSTAINED_MINUTES}"
info "Local results dir:     ${LOCAL_RESULTS_DIR}"
info "Remote gradle command: ${GRADLE_CMD}"

if [[ "${DRY_RUN}" == "true" ]]; then
    ok "Dry run — no actions performed."
    exit 0
fi

# ---------------------------------------------------------------------------
# Step 1 — Reachability check
# ---------------------------------------------------------------------------
header "Step 1 — Reachability"
if ! ssh -o ConnectTimeout=5 "${PI_HOST}" "echo ok" >/dev/null 2>&1; then
    fail "Pi unreachable at ${PI_HOST}."
    exit 1
fi
ok "${PI_HOST} reachable"

# ---------------------------------------------------------------------------
# Step 2 — Prepare local results dir
# ---------------------------------------------------------------------------
header "Step 2 — Local results directory"
mkdir -p "${LOCAL_RESULTS_DIR}"
ok "Created ${LOCAL_RESULTS_DIR}"

# ---------------------------------------------------------------------------
# Step 3 — Sync source to the Pi
# ---------------------------------------------------------------------------
header "Step 3 — Sync source to ${PI_HOST}:${PI_PROJECT_DIR}"
ssh "${PI_HOST}" "mkdir -p '${PI_PROJECT_DIR}'"
rsync -az --delete \
    --exclude=build \
    --exclude=.gradle \
    --exclude=.git \
    --exclude='pi4-validation-*' \
    ./ "${PI_HOST}:${PI_PROJECT_DIR}/"
ok "Source synced"

# ---------------------------------------------------------------------------
# Step 4 — Run gradle remotely; capture exit code
# ---------------------------------------------------------------------------
header "Step 4 — Run integration tests"
info "This will take roughly $((SUSTAINED_MINUTES + 35)) minutes."

GRADLE_EXIT_CODE=0
ssh -t "${PI_HOST}" \
    "bash -lc 'cd \"${PI_PROJECT_DIR}\" && ${GRADLE_CMD}'" \
    2>&1 | tee "${LOCAL_RESULTS_DIR}/gradle.log" \
    || GRADLE_EXIT_CODE=$?

if [[ "${GRADLE_EXIT_CODE}" -eq 0 ]]; then
    ok "Gradle run finished with exit code 0"
else
    warn "Gradle run finished with exit code ${GRADLE_EXIT_CODE} — collecting reports anyway"
fi

# ---------------------------------------------------------------------------
# Step 5 — Pull JUnit results
# ---------------------------------------------------------------------------
header "Step 5 — Pull test-results"
rsync -az \
    "${PI_HOST}:${REMOTE_RESULTS_PATH}/test-results/" \
    "${LOCAL_RESULTS_DIR}/test-results/" \
    || warn "test-results directory not present (run may have failed early)"
ok "test-results pulled"

# ---------------------------------------------------------------------------
# Step 6 — Pull HTML reports + JFR
# ---------------------------------------------------------------------------
header "Step 6 — Pull reports"
rsync -az \
    "${PI_HOST}:${REMOTE_RESULTS_PATH}/reports/" \
    "${LOCAL_RESULTS_DIR}/reports/" \
    || warn "reports directory not present (run may have failed early)"
ok "reports pulled"

# ---------------------------------------------------------------------------
# Step 7 — Summary
# ---------------------------------------------------------------------------
header "Summary"
info "Results:    ${LOCAL_RESULTS_DIR}"
info "Gradle log: ${LOCAL_RESULTS_DIR}/gradle.log"
if [[ "${GRADLE_EXIT_CODE}" -eq 0 ]]; then
    ok "Validation PASSED"
else
    fail "Validation FAILED (exit code ${GRADLE_EXIT_CODE})"
fi

exit "${GRADLE_EXIT_CODE}"
