#!/usr/bin/env bash
# =============================================================================
# HomeSynapse Core — Full Clean
#
# Remove ALL build artifacts across every module, including (especially) build-logic
# and any orphan build/ directories left behind by removed modules.
#
# Usage:
#   ./scripts/clean.sh          # standard clean
#   ./scripts/clean.sh --deep   # also removes .gradle caches and IDE files
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

DEEP=false
if [[ "${1:-}" == "--deep" ]]; then
    DEEP=true
fi

echo "=== HomeSynapse Core — Full Clean ==="
echo "    repo: $REPO_ROOT"
echo ""

# 1. Gradle clean (all 19 subprojects)
echo "[1/4] Running ./gradlew clean ..."
./gradlew clean --quiet 2>/dev/null || {
    echo "       (gradlew clean failed — removing build/ dirs manually)"
}

# 2. build-logic (included build, not covered by root clean)
echo "[2/4] Cleaning build-logic ..."
if [[ -d "build-logic/build" ]]; then
    rm -rf build-logic/build
fi

# 3. Orphan build/ directories
#    Catches: root build/, modules removed from settings.gradle.kts but whose
#    directories still exist, or anything Gradle's clean task missed.
echo "[3/4] Removing orphan build/ directories ..."
find "$REPO_ROOT" -name "build" -type d \
    -not -path "*/.gradle/*" \
    -not -path "*/node_modules/*" \
    -not -path "*/src/*" \
    -exec rm -rf {} + 2>/dev/null || true

# 4. Deep clean (optional)
if $DEEP; then
    echo "[4/4] Deep clean — removing .gradle caches and IDE files ..."
    rm -rf "$REPO_ROOT/.gradle"
    rm -rf "$REPO_ROOT/build-logic/.gradle"
    rm -rf "$REPO_ROOT/.idea"
    find "$REPO_ROOT" -name "*.iml" -delete 2>/dev/null || true
    find "$REPO_ROOT" -name ".DS_Store" -delete 2>/dev/null || true
    find "$REPO_ROOT" -name "Thumbs.db" -delete 2>/dev/null || true
    find "$REPO_ROOT" -name "*.log" -delete 2>/dev/null || true
    find "$REPO_ROOT" -name "hs_err_pid*" -delete 2>/dev/null || true
else
    echo "[4/4] Skipping deep clean (use --deep to also remove .gradle caches and IDE files)"
fi

echo ""
echo "=== Squeaky clean repo. ==="
