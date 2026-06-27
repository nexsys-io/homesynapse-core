#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi-clean.sh — Clean Pi state for fresh test runs

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-pi}"
PI_USER="${PI_USER:-homesynapse}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-homesynapse-core}"

FORCE=false
CLEAN_ALL=false

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
Usage: $(basename "$0") [--force] [--all] [--help]

Remove stale state from the Pi for a clean test run.

Options:
  --force    Skip confirmation prompt
  --all      Also clean Gradle caches (slow to rebuild, opt-in only)
  -h, --help Show this help message

What gets removed:
  - SQLite databases in /var/lib/homesynapse/ (*.db, *.db-wal, *.db-shm)
  - JFR recordings in /var/lib/homesynapse/ (*.jfr)
  - Temp files in /var/lib/homesynapse/tmp/
  [with --all] Gradle caches in ~/.gradle/caches/

Environment variables:
  PI_HOST        SSH host alias (default: pi)
  PI_USER        Pi username (default: homesynapse)
  PI_PROJECT_DIR Remote project directory (default: homesynapse-core)

Examples:
  ./scripts/dev/pi-clean.sh
  ./scripts/dev/pi-clean.sh --force
  ./scripts/dev/pi-clean.sh --all --force
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)   FORCE=true; shift ;;
        --all)     CLEAN_ALL=true; shift ;;
        -h|--help) usage ;;
        *)         fail "Unknown option: $1"; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Show what will be cleaned
# ---------------------------------------------------------------------------
header "Clean Pi State — ${PI_HOST}"

printf "\n"
info "The following will be removed from ${PI_HOST}:"
printf "  - SQLite databases in /var/lib/homesynapse/ (*.db, *.db-wal, *.db-shm)\n"
printf "  - JFR recordings in /var/lib/homesynapse/ (*.jfr)\n"
printf "  - Temp files in /var/lib/homesynapse/tmp/\n"
if [ "$CLEAN_ALL" = true ]; then
    printf "  - Gradle caches in ~/.gradle/caches/\n"
fi
printf "\n"

# ---------------------------------------------------------------------------
# 2. Confirm (unless --force)
# ---------------------------------------------------------------------------
if [ "$FORCE" != true ]; then
    read -p "Proceed? [y/N] " confirm
    case "$confirm" in
        [yY]|[yY][eE][sS]) ;;
        *)
            info "Aborted."
            exit 0
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# 3. Execute cleanup
# ---------------------------------------------------------------------------
info "Cleaning..."

ssh "${PI_HOST}" "bash -lc '
    rm -f /var/lib/homesynapse/*.db /var/lib/homesynapse/*.db-wal /var/lib/homesynapse/*.db-shm
    rm -f /var/lib/homesynapse/*.jfr
    rm -rf /var/lib/homesynapse/tmp/*
'"

ok "Removed databases, JFR recordings, and temp files"

if [ "$CLEAN_ALL" = true ]; then
    info "Cleaning Gradle caches (next build will re-download dependencies)..."
    ssh "${PI_HOST}" "rm -rf ~/.gradle/caches/"
    ok "Removed Gradle caches"
fi

# ---------------------------------------------------------------------------
# 4. Verify cleanup
# ---------------------------------------------------------------------------
REMAINING=$(ssh "${PI_HOST}" "ls /var/lib/homesynapse/ 2>/dev/null | wc -l" | tr -d '[:space:]')
info "Remaining files in /var/lib/homesynapse/: ${REMAINING}"

# ---------------------------------------------------------------------------
# 5. Summary
# ---------------------------------------------------------------------------
printf "\n"
ok "Pi cleaned. Ready for fresh test run."
exit 0
