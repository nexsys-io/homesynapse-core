#!/usr/bin/env bash
# =============================================================================
# HomeSynapse Core — THROWAWAY SPIKE: dispatch-latency-and-log-growth
# Disposable. Slated for `git rm`. NOT production code.
#
# Runs both benchmarks UNCHANGED on any host (dev x86_64 OR a Raspberry Pi):
#   B1 = dispatch-seam latency (direct call vs co-located event-driven hop) +D2
#   B2 = SQLite-WAL log-growth (bytes/event, append ev/s) + projection-replay time
#
# Pi usage (the whole point): copy this spike dir to the Pi and run:
#     ./run.sh
# It auto-detects javac, fetches the sqlite-jdbc driver if missing, compiles,
# and runs. Then compare the printed numbers to the dev baseline in the report
# (context/assessments/2026-06-26_pi-dispatch-latency-and-log-growth_spike.md).
#
# IMPORTANT (storage): the DB benchmark MUST run on a real local filesystem
# (ext4 / the SD card), NOT on a network/overlay/FUSE mount — SQLite WAL needs
# real file ops (mmap, unlink, shared-memory). This script writes its scratch
# DBs to a local TMP dir ($DBDIR below), never into the repo tree.
# =============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src/com/homesynapse/spike/dispatch"
LIB="$HERE/lib"
OUT="${SPIKE_OUT:-/tmp/hs-spike-out}"          # compiled classes (local, writable)
DBDIR="${SPIKE_DBDIR:-/tmp/hs-spike-db}"        # scratch DBs (local real FS)

# --- Benchmark sizes (override via env for a quick smoke or a Pi-scaled run) ---
B1_WARMUP="${B1_WARMUP:-2000000}"
B1_TRIALS="${B1_TRIALS:-20}"
B1_OPS="${B1_OPS:-20000000}"
B2_ENTITIES="${B2_ENTITIES:-200}"
B2_BATCH="${B2_BATCH:-500}"
B2_NS="${B2_NS:-10000 100000 1000000}"          # Pi: maybe drop 1e6 if disk-tight

mkdir -p "$OUT" "$DBDIR"

# ---------------------------------------------------------------------------
# 1. Locate a JDK with javac. Prefer $JAVA_HOME, then PATH, then known dirs.
#    If only a JRE is present (no javac), fetch a portable Temurin 21 to /tmp.
# ---------------------------------------------------------------------------
find_javac() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then echo "$JAVA_HOME"; return; fi
  if command -v javac >/dev/null 2>&1; then dirname "$(dirname "$(command -v javac)")"; return; fi
  for d in /usr/lib/jvm/*/ /tmp/jdk21; do
    [ -x "$d/bin/javac" ] && { echo "${d%/}"; return; }
  done
  echo ""
}
JH="$(find_javac)"
if [ -z "$JH" ]; then
  echo ">> No javac found. Fetching portable Temurin 21 (HomeSynapse target) to /tmp/jdk21 ..."
  ARCH="$(uname -m)"; case "$ARCH" in x86_64) A=x64;; aarch64|arm64) A=aarch64;; armv7l) A=arm;; *) A="$ARCH";; esac
  mkdir -p /tmp/jdk21
  URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${A}/jdk/hotspot/normal/eclipse?project=jdk"
  curl -sSL --fail -o /tmp/temurin21.tgz "$URL"
  tar -xzf /tmp/temurin21.tgz -C /tmp/jdk21 --strip-components=1
  JH=/tmp/jdk21
fi
export JAVA_HOME="$JH"
export PATH="$JAVA_HOME/bin:$PATH"
echo ">> Using JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---------------------------------------------------------------------------
# 2. Ensure the sqlite-jdbc + slf4j jars are present (fetch from Maven Central).
# ---------------------------------------------------------------------------
mkdir -p "$LIB"
fetch() { # url dest
  [ -s "$2" ] && return
  echo ">> fetching $(basename "$2")"
  curl -sSL --fail -o "$2" "$1"
}
SQLITE_VER="${SQLITE_VER:-3.45.3.0}"   # JDK11+ safe; bundles native libs incl. aarch64/arm
fetch "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/${SQLITE_VER}/sqlite-jdbc-${SQLITE_VER}.jar" "$LIB/sqlite-jdbc.jar"
fetch "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar" "$LIB/slf4j-api.jar"
fetch "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar" "$LIB/slf4j-nop.jar"
CP="$LIB/sqlite-jdbc.jar:$LIB/slf4j-api.jar:$LIB/slf4j-nop.jar"

# ---------------------------------------------------------------------------
# 3. Compile (to a LOCAL out dir; never into the repo tree on FUSE mounts).
# ---------------------------------------------------------------------------
echo ">> compiling ..."
javac -d "$OUT" -cp "$CP" "$SRC/DispatchLatencyBenchmark.java" "$SRC/LogGrowthBenchmark.java"

# ---------------------------------------------------------------------------
# 4. Run both benchmarks. Record host facts first (for the Pi comparison).
# ---------------------------------------------------------------------------
echo
echo "############### HOST FACTS ###############"
echo "uname: $(uname -srm)"
( command -v lscpu >/dev/null && lscpu | grep -E 'Model name|^CPU\(s\)|CPU max MHz|BogoMIPS' ) || true
echo "DBDIR filesystem:"; df -T "$DBDIR" | tail -1
echo "##########################################"
echo
echo "############### BENCHMARK 1 (dispatch-seam latency + D2) ###############"
java -cp "$OUT:$CP" com.homesynapse.spike.dispatch.DispatchLatencyBenchmark "$B1_WARMUP" "$B1_TRIALS" "$B1_OPS"
echo
echo "############### BENCHMARK 2 (log growth + replay) ###############"
# shellcheck disable=SC2086
java -cp "$OUT:$CP" com.homesynapse.spike.dispatch.LogGrowthBenchmark "$DBDIR" "$B2_ENTITIES" "$B2_BATCH" $B2_NS

echo
echo ">> done. Scratch DBs were written under $DBDIR (safe to delete)."
echo ">> Grep the lines starting RESULT_B1 / RESULT_B2 / RESULT_B2C for machine-readable numbers."
