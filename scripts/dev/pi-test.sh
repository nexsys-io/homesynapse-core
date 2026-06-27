#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi-test.sh — Run Gradle tasks on the Pi remotely

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-pi}"
PI_USER="${PI_USER:-homesynapse}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-homesynapse-core}"

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

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
info()   { printf "${BLUE}[INFO]${NC}  %s\n" "$1"; }
ok()     { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn()   { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail()   { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }
header() { printf "\n${BOLD}=== %s ===${NC}\n" "$1"; }

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: $(basename "$0") <gradle-task> [extra-args...]

Execute a Gradle task on the Pi and stream output to your terminal.

Arguments:
  gradle-task    The Gradle task to run (required)
  extra-args     Additional arguments passed to Gradle

Options:
  -h, --help     Show this help message

Environment variables:
  PI_HOST        SSH host alias (default: pi)
  PI_USER        Pi username (default: homesynapse)
  PI_PROJECT_DIR Remote project directory (default: homesynapse-core)

Examples:
  ./scripts/dev/pi-test.sh :core:event-bus:check
  ./scripts/dev/pi-test.sh :core:event-model:test --tests "*EventStoreContractTest*"
  ./scripts/dev/pi-test.sh check
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
if [[ $# -eq 0 ]]; then
    fail "No Gradle task specified."
    printf "\n"
    usage
fi

case "$1" in
    -h|--help) usage ;;
esac

# ---------------------------------------------------------------------------
# 1. Quick health check
# ---------------------------------------------------------------------------
header "Remote Test — ${PI_HOST}"

if ! ssh -o ConnectTimeout=5 "${PI_HOST}" "echo ok" >/dev/null 2>&1; then
    fail "Pi unreachable. Run pi-health.sh for diagnostics."
    exit 1
fi
ok "Pi reachable"

# ---------------------------------------------------------------------------
# 2. Execute remotely
# ---------------------------------------------------------------------------
GRADLE_ARGS="$*"
info "Running: ./gradlew ${GRADLE_ARGS} on ${PI_HOST}"
printf "\n"

EXIT_CODE=0
ssh -t "${PI_HOST}" "bash -lc 'cd ~/${PI_PROJECT_DIR} && ./gradlew ${GRADLE_ARGS}'" || EXIT_CODE=$?

# ---------------------------------------------------------------------------
# 3. Report result
# ---------------------------------------------------------------------------
printf "\n"
if [ "$EXIT_CODE" -eq 0 ]; then
    ok "Tests passed"
else
    fail "Tests failed (exit code: ${EXIT_CODE})"
fi

exit "$EXIT_CODE"
