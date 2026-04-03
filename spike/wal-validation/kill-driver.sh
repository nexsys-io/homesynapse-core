#!/bin/sh
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# kill-driver.sh — Orchestrates C3: Kill -9 Durability trials.
# POSIX-compatible shell script for Debian Bookworm or later (aarch64).
#
# Usage: kill-driver.sh <classpath> <db_path> <num_trials>
#
# Arguments:
#   classpath   — Java classpath (e.g., build/libs/wal-validation.jar or build/classes/...)
#   db_path     — Path for the SQLite database file
#   num_trials  — Number of kill/verify cycles to run (typically 5)
#
# Per trial:
#   1. Cleans DB files from any previous trial
#   2. Starts the C3 writer in background (sustained 100 inserts/sec)
#   3. Waits a random 5–15 seconds
#   4. Sends SIGKILL to the writer
#   5. Waits 2 seconds for filesystem flush
#   6. Runs C3 verify to count events and compare to sidecar
#
# Design choice: DB files are deleted between trials for clean isolation.
# Each trial starts with a fresh database.

if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <classpath> <db_path> <num_trials>"
    echo ""
    echo "Example:"
    echo "  $0 build/libs/wal-validation-all.jar /tmp/c3-test.db 5"
    exit 1
fi

CLASSPATH="$1"
DB_PATH="$2"
NUM_TRIALS="$3"
CLASS="com.homesynapse.spike.wal.C3KillDurabilityTest"

total_sidecar=0
total_recovered=0
passed=0
trial=1

echo "=== C3: Kill -9 Durability — Starting $NUM_TRIALS trials ==="
echo "  Classpath: $CLASSPATH"
echo "  DB path:   $DB_PATH"
echo ""

while [ "$trial" -le "$NUM_TRIALS" ]; do
    echo "--- Trial $trial of $NUM_TRIALS ---"

    # 1. Clean DB files for a fresh trial
    rm -f "$DB_PATH" "${DB_PATH}-wal" "${DB_PATH}-shm" "${DB_PATH}.count"

    # 2. Start writer in background
    java -cp "$CLASSPATH" "$CLASS" "$DB_PATH" writer &
    WRITER_PID=$!
    echo "Writer PID: $WRITER_PID"

    # 3. Wait random 5–15 seconds (POSIX-compatible random via awk)
    WAIT_SEC=$(awk 'BEGIN{srand(); printf "%d", 5 + int(rand() * 11)}')
    echo "Waiting ${WAIT_SEC}s before SIGKILL..."
    sleep "$WAIT_SEC"

    # 4. Send SIGKILL
    kill -9 "$WRITER_PID" 2>/dev/null || true
    wait "$WRITER_PID" 2>/dev/null || true
    echo "Sent SIGKILL to PID $WRITER_PID"

    # 5. Wait for filesystem flush / WAL recovery window
    sleep 2

    # 6. Run verify mode
    OUTPUT=$(java -cp "$CLASSPATH" "$CLASS" "$DB_PATH" verify "$trial")
    echo "$OUTPUT"

    # Parse results — strip commas from formatted numbers
    SIDECAR=$(echo "$OUTPUT" | grep "Events in sidecar" | awk -F': ' '{print $2}' | tr -d ',')
    DB_COUNT=$(echo "$OUTPUT" | grep "Events in database" | awk -F': ' '{print $2}' | tr -d ',')
    RESULT=$(echo "$OUTPUT" | grep "^RESULT:" | awk '{print $2}')

    if [ -n "$SIDECAR" ]; then
        total_sidecar=$((total_sidecar + SIDECAR))
    fi
    if [ -n "$DB_COUNT" ]; then
        total_recovered=$((total_recovered + DB_COUNT))
    fi
    if [ "$RESULT" = "PASS" ]; then
        passed=$((passed + 1))
    fi

    echo ""
    trial=$((trial + 1))
done

# Aggregate summary
echo "=== C3: Kill -9 Durability Summary ==="
echo "Trials: $NUM_TRIALS"
echo "Passed: $passed/$NUM_TRIALS"
echo "Total events written: $total_sidecar"
echo "Total events recovered: $total_recovered"
if [ "$passed" -eq "$NUM_TRIALS" ]; then
    echo "RESULT: PASS"
else
    echo "RESULT: FAIL"
fi
