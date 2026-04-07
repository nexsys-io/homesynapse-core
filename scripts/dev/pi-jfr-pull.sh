#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi-jfr-pull.sh — Pull JFR recordings from the Pi to the dev machine

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-pi}"
PI_USER="${PI_USER:-homesynapse}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-homesynapse-core}"

DEST_DIR="./jfr-recordings"

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
Usage: $(basename "$0") [--dest DIR] [--help]

Copy JFR (Java Flight Recorder) files from the Pi to your dev machine
for analysis in JDK Mission Control.

Options:
  --dest DIR   Local destination directory (default: ./jfr-recordings/)
  -h, --help   Show this help message

Environment variables:
  PI_HOST        SSH host alias (default: pi)
  PI_USER        Pi username (default: homesynapse)
  PI_PROJECT_DIR Remote project directory (default: homesynapse-core)

JFR search locations on Pi:
  /var/lib/homesynapse/*.jfr    (standard FHS output location)
  ~/${PI_PROJECT_DIR}/**/*.jfr  (Gradle test run output)

Examples:
  ./scripts/dev/pi-jfr-pull.sh
  ./scripts/dev/pi-jfr-pull.sh --dest ~/analysis/jfr
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dest)
            if [[ $# -lt 2 ]]; then fail "--dest requires a directory argument"; exit 2; fi
            DEST_DIR="$2"; shift 2 ;;
        -h|--help) usage ;;
        *)         fail "Unknown option: $1"; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Create local destination
# ---------------------------------------------------------------------------
header "Pull JFR Recordings"
mkdir -p "${DEST_DIR}"

# ---------------------------------------------------------------------------
# 2. Find remote JFR files (both FHS location and project dir)
# ---------------------------------------------------------------------------
info "Searching for JFR recordings on ${PI_HOST}..."

JFR_FILES=$(ssh "${PI_HOST}" "
    find /var/lib/homesynapse -name '*.jfr' -type f 2>/dev/null || true
    find ~/${PI_PROJECT_DIR} -name '*.jfr' -type f 2>/dev/null || true
" | sort -u)

# ---------------------------------------------------------------------------
# 3. Check if any files found
# ---------------------------------------------------------------------------
if [ -z "$JFR_FILES" ]; then
    info "No JFR recordings found on Pi"
    exit 0
fi

FILE_COUNT=$(echo "$JFR_FILES" | wc -l | tr -d '[:space:]')
info "Found ${FILE_COUNT} JFR recording(s)"

# ---------------------------------------------------------------------------
# 4. Copy with timestamp prefix
# ---------------------------------------------------------------------------
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PULLED=0
PULL_SUMMARY=""

while IFS= read -r file; do
    [ -z "$file" ] && continue
    BASENAME=$(basename "${file}")
    LOCAL_NAME="${TIMESTAMP}_${BASENAME}"
    LOCAL_PATH="${DEST_DIR}/${LOCAL_NAME}"

    info "Pulling: ${file}"
    scp "${PI_HOST}:${file}" "${LOCAL_PATH}"

    # Get file size in a cross-platform way
    if [ -f "$LOCAL_PATH" ]; then
        # wc -c works everywhere; format to human-readable
        SIZE_BYTES=$(wc -c < "$LOCAL_PATH" | tr -d '[:space:]')
        if [ "$SIZE_BYTES" -ge 1048576 ]; then
            SIZE_HUMAN=$(awk "BEGIN {printf \"%.1f MB\", ${SIZE_BYTES}/1048576}")
        elif [ "$SIZE_BYTES" -ge 1024 ]; then
            SIZE_HUMAN=$(awk "BEGIN {printf \"%.0f KB\", ${SIZE_BYTES}/1024}")
        else
            SIZE_HUMAN="${SIZE_BYTES} B"
        fi
        PULL_SUMMARY="${PULL_SUMMARY}  - ${LOCAL_NAME} (${SIZE_HUMAN})\n"
    fi

    PULLED=$((PULLED + 1))
done <<< "$JFR_FILES"

# ---------------------------------------------------------------------------
# 5. Summary
# ---------------------------------------------------------------------------
printf "\n"
ok "Pulled ${PULLED} JFR recording(s) to ${DEST_DIR}/"
printf "${PULL_SUMMARY}"
exit 0
