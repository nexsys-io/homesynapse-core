#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi-deploy.sh — Deploy compiled code from dev machine to Pi

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-pi}"
PI_USER="${PI_USER:-homesynapse}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-homesynapse-core}"

SKIP_BUILD=false
MODULE=""

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
Usage: $(basename "$0") [--skip-build] [--module MODULE] [--help]

Build locally and deploy HomeSynapse code to the Pi.

Options:
  --skip-build     Skip the local ./gradlew assemble step
  --module MODULE  Deploy only a specific module (e.g., :core:event-model)
  -h, --help       Show this help message

Environment variables:
  PI_HOST        SSH host alias (default: pi)
  PI_USER        Pi username (default: homesynapse)
  PI_PROJECT_DIR Remote project directory (default: homesynapse-core)

Examples:
  ./scripts/dev/pi-deploy.sh
  ./scripts/dev/pi-deploy.sh --skip-build
  ./scripts/dev/pi-deploy.sh --module :core:event-bus
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)  SKIP_BUILD=true; shift ;;
        --module)
            if [[ $# -lt 2 ]]; then fail "--module requires an argument"; exit 2; fi
            MODULE="$2"; shift 2 ;;
        -h|--help)     usage ;;
        *)             fail "Unknown option: $1"; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Verify repo root
# ---------------------------------------------------------------------------
header "Deploy to ${PI_HOST}"

if [ ! -f "settings.gradle.kts" ] && [ ! -f "gradlew" ]; then
    fail "Not in the homesynapse-core repo root (settings.gradle.kts and gradlew not found)."
    fail "Run this script from the root of your homesynapse-core checkout."
    exit 2
fi

ok "Repository root detected"

# ---------------------------------------------------------------------------
# 2. Local build (unless --skip-build)
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = true ]; then
    info "Skipping local build (--skip-build)"
else
    header "Local Build"
    BUILD_CMD="./gradlew"

    # Windows/Git Bash: prefer gradlew.bat if it exists
    if [ -f "gradlew.bat" ] && [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
        BUILD_CMD="./gradlew.bat"
    fi

    if [ -n "$MODULE" ]; then
        info "Building module: ${MODULE}"
        ${BUILD_CMD} "${MODULE}:assemble"
    else
        info "Building all modules..."
        ${BUILD_CMD} assemble
    fi

    ok "Local build complete"
fi

# ---------------------------------------------------------------------------
# 3. Deploy
# ---------------------------------------------------------------------------
header "Deploying"

EXCLUDE_ARGS=(
    --exclude='.gradle'
    --exclude='**/build'
    --exclude='.git'
    --exclude='.idea'
    --exclude='*.iml'
    --exclude='local.properties'
)

if command -v rsync >/dev/null 2>&1; then
    # rsync path (Linux/macOS, or Windows with rsync installed)
    info "Using rsync for deployment..."
    rsync -avz --progress \
        "${EXCLUDE_ARGS[@]}" \
        ./ "${PI_HOST}:~/${PI_PROJECT_DIR}/"
else
    # tar+scp fallback (Windows/Git Bash without rsync)
    info "rsync not available — using tar+scp fallback..."

    TAR_EXCLUDE_ARGS=(
        --exclude='.gradle'
        --exclude='build'
        --exclude='.git'
        --exclude='.idea'
        --exclude='*.iml'
        --exclude='local.properties'
    )

    TMPFILE="/tmp/hs-deploy.tar.gz"
    info "Creating archive..."
    tar czf "${TMPFILE}" "${TAR_EXCLUDE_ARGS[@]}" .

    info "Uploading to ${PI_HOST}..."
    scp "${TMPFILE}" "${PI_HOST}:~/hs-deploy.tar.gz"

    info "Extracting on Pi..."
    ssh "${PI_HOST}" "mkdir -p ~/${PI_PROJECT_DIR} && cd ~/${PI_PROJECT_DIR} && tar xzf ~/hs-deploy.tar.gz && rm ~/hs-deploy.tar.gz"

    rm -f "${TMPFILE}"
fi

# ---------------------------------------------------------------------------
# 4. Verify deployment
# ---------------------------------------------------------------------------
header "Verification"
JAVA_FILE_COUNT=$(ssh "${PI_HOST}" "find ~/${PI_PROJECT_DIR} -name '*.java' | wc -l" | tr -d '[:space:]')

ok "Deployed to ${PI_HOST}:~/${PI_PROJECT_DIR}/ (${JAVA_FILE_COUNT} Java files)"
exit 0
