#!/usr/bin/env bash
# HomeSynapse Core
# Copyright (c) 2026 NexSys. All rights reserved.
#
# pi-health.sh — Preflight Pi health check for development sessions
#
# 2026-06-12 additions (pre-OQ-15-2 microbench session): throttle-flag check
# (vcgencmd bitfield incl. since-boot history — benchmark validity), clock
# sync, NVMe mount options (noatime), swap usage, on-Pi repo state
# (PI_PROJECT_DIR is now actually checked), reboot-required, and a final
# machine-parseable READINESS: line for agent-assisted (Claude) workflows.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (override via environment variables)
# ---------------------------------------------------------------------------
PI_HOST="${PI_HOST:-pi}"
PI_USER="${PI_USER:-homesynapse}"
PI_PROJECT_DIR="${PI_PROJECT_DIR:-homesynapse-core}"

VERBOSE=false

# ---------------------------------------------------------------------------
# Colors — ANSI escape codes only (no tput — Git Bash/MINGW64 compat)
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

verbose() {
    if [ "$VERBOSE" = true ]; then
        printf "${BLUE}[CMD]${NC}   %s\n" "$1"
    fi
}

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: $(basename "$0") [--verbose] [--help]

Preflight health check for the HomeSynapse Pi development target.
Run before any test session to verify the Pi is ready.

Options:
  --verbose    Print SSH commands before executing (debug SSH/Tailscale)
  -h, --help   Show this help message

Environment variables:
  PI_HOST        SSH host alias (default: pi)
  PI_USER        Pi username (default: homesynapse)
  PI_PROJECT_DIR Remote project directory (default: homesynapse-core)

Examples:
  ./scripts/dev/pi-health.sh
  ./scripts/dev/pi-health.sh --verbose
  PI_HOST=pi2 ./scripts/dev/pi-health.sh
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --verbose)  VERBOSE=true; shift ;;
        -h|--help)  usage ;;
        *)          fail "Unknown option: $1"; usage ;;
    esac
done

# ---------------------------------------------------------------------------
# State tracking
# ---------------------------------------------------------------------------
FAILURES=0

check_pass() { ok "$1"; }
check_fail() { fail "$1"; FAILURES=$((FAILURES + 1)); }
check_warn() { warn "$1"; }

# ---------------------------------------------------------------------------
# 1. SSH Connectivity
# ---------------------------------------------------------------------------
header "Pi Health Check — ${PI_HOST}"

info "Checking SSH connectivity..."
verbose "ssh -o ConnectTimeout=10 -o BatchMode=yes ${PI_HOST} echo ok"
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${PI_HOST}" "echo ok" >/dev/null 2>&1; then
    check_fail "Cannot reach Pi at '${PI_HOST}'. Is Tailscale running?"
    printf "\n"
    printf "${BOLD}═══════════════════════════════════════${NC}\n"
    printf "${BOLD}  Pi Health: 1 CHECK(S) FAILED ✗${NC}\n"
    printf "${BOLD}═══════════════════════════════════════${NC}\n"
    exit 1
fi
check_pass "SSH connectivity"

# ---------------------------------------------------------------------------
# 2–9. Batched remote data collection
#
# Gather all diagnostic data in a single SSH call to minimize round trips.
# Each value is emitted on a labeled line for reliable parsing.
# ---------------------------------------------------------------------------
verbose "ssh ${PI_HOST} bash -ls  (batched diagnostic script via stdin)"

# Send the remote script via heredoc-over-stdin to avoid quoting hell.
# The heredoc delimiter is single-quoted ('HEALTHCHECK') so the LOCAL shell
# performs zero expansion — every $, ", and \ is passed verbatim to the
# remote bash -ls (login shell, read from stdin).
REMOTE_DATA=$(ssh "${PI_HOST}" PI_PROJECT_DIR="${PI_PROJECT_DIR}" bash -ls <<'HEALTHCHECK'
echo "HOSTNAME:$(hostname)"
echo "PRETTY_NAME:$(grep PRETTY_NAME /etc/os-release 2>/dev/null | cut -d= -f2 | tr -d '"')"
echo "TEMP:$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo ERROR)"

MEM_LINE=$(free -m | grep Mem)
MEM_TOTAL=$(echo "$MEM_LINE" | awk '{print $2}')
MEM_AVAIL=$(echo "$MEM_LINE" | awk '{print $7}')
echo "MEM_TOTAL:$MEM_TOTAL"
echo "MEM_AVAIL:$MEM_AVAIL"

if mountpoint -q /mnt/nvme 2>/dev/null; then echo "NVME_MOUNTED:yes"; else echo "NVME_MOUNTED:no"; fi
if test -L /var/lib/homesynapse 2>/dev/null; then echo "SYMLINK:yes"; else echo "SYMLINK:no"; fi
DISK_LINE=$(df -h /mnt/nvme 2>/dev/null | tail -1)
DISK_USE=$(echo "$DISK_LINE" | awk '{print $5}' | tr -d %)
DISK_AVAIL=$(echo "$DISK_LINE" | awk '{print $4}')
DISK_TOTAL=$(echo "$DISK_LINE" | awk '{print $2}')
echo "DISK_USE:${DISK_USE:-UNKNOWN}"
echo "DISK_AVAIL:${DISK_AVAIL:-UNKNOWN}"
echo "DISK_TOTAL:${DISK_TOTAL:-UNKNOWN}"

JAVA_VER_FULL=$(java -version 2>&1 || echo ERROR)
JAVA_VER_LINE1=$(echo "$JAVA_VER_FULL" | head -1)
JAVA_VER_HAS_CORRETTO=$(echo "$JAVA_VER_FULL" | grep -ci corretto || true)
echo "JAVA_VERSION:$JAVA_VER_LINE1"
echo "JAVA_CORRETTO:$JAVA_VER_HAS_CORRETTO"

TS_ONLINE=$(tailscale status --json 2>/dev/null | grep -Eo '"Online": ?true' | head -1 || echo offline)
echo "TAILSCALE:$TS_ONLINE"

echo "UPTIME:$(uptime)"

# Throttle bitfield: authoritative benchmark-validity signal.
# vcgencmd path differs across OS images; try both, fall back to sysfs-absent.
THROTTLED=$( (command -v vcgencmd >/dev/null && vcgencmd get_throttled) 2>/dev/null || (test -x /usr/bin/vcgencmd && /usr/bin/vcgencmd get_throttled) 2>/dev/null || echo "throttled=UNAVAILABLE")
echo "THROTTLED:${THROTTLED#throttled=}"

echo "NTP_SYNC:$(timedatectl show -p NTPSynchronized --value 2>/dev/null || echo UNKNOWN)"

echo "NVME_OPTS:$(findmnt -no OPTIONS /mnt/nvme 2>/dev/null || echo UNKNOWN)"

SWAP_LINE=$(free -m | grep -i swap)
echo "SWAP_TOTAL:$(echo "$SWAP_LINE" | awk '{print $2}')"
echo "SWAP_USED:$(echo "$SWAP_LINE" | awk '{print $3}')"

if [ -d "$HOME/$PI_PROJECT_DIR/.git" ]; then
    echo "REPO_PRESENT:yes"
    echo "REPO_HEAD:$(git -C "$HOME/$PI_PROJECT_DIR" log --oneline -1 2>/dev/null | cut -c1-60)"
    echo "REPO_DIRTY:$(git -C "$HOME/$PI_PROJECT_DIR" status --porcelain 2>/dev/null | wc -l)"
else
    echo "REPO_PRESENT:no"
    echo "REPO_HEAD:"
    echo "REPO_DIRTY:"
fi

if [ -f /var/run/reboot-required ]; then echo "REBOOT_REQUIRED:yes"; else echo "REBOOT_REQUIRED:no"; fi

FHS_COUNT=0
for d in /var/lib/homesynapse /var/lib/homesynapse/tmp /mnt/nvme/homesynapse/backups /var/log/homesynapse /etc/homesynapse /opt/homesynapse; do
    if [ -e "$d" ]; then FHS_COUNT=$((FHS_COUNT+1)); fi
done
echo "FHS_COUNT:$FHS_COUNT"

FHS_MISSING=""
for d in /var/lib/homesynapse /var/lib/homesynapse/tmp /mnt/nvme/homesynapse/backups /var/log/homesynapse /etc/homesynapse /opt/homesynapse; do
    if [ ! -e "$d" ]; then FHS_MISSING="${FHS_MISSING} $d"; fi
done
echo "FHS_MISSING:$FHS_MISSING"
HEALTHCHECK
)

# ---------------------------------------------------------------------------
# Parse helper
# ---------------------------------------------------------------------------
get_val() {
    echo "$REMOTE_DATA" | grep "^$1:" | head -1 | cut -d: -f2-
}

# ---------------------------------------------------------------------------
# 2. Hostname & OS
# ---------------------------------------------------------------------------
header "System Info"
PI_HOSTNAME=$(get_val HOSTNAME)
PI_OS=$(get_val PRETTY_NAME)
info "Hostname: ${PI_HOSTNAME}"
info "OS: ${PI_OS}"

# ---------------------------------------------------------------------------
# 3. CPU Temperature
# ---------------------------------------------------------------------------
header "CPU Temperature"
TEMP_RAW=$(get_val TEMP)
if [ "$TEMP_RAW" = "ERROR" ] || [ -z "$TEMP_RAW" ]; then
    check_fail "Could not read CPU temperature"
else
    TEMP_C=$(echo "$TEMP_RAW" | awk '{printf "%.1f", $1/1000}')
    TEMP_INT=${TEMP_RAW%???}  # integer division: drop last 3 digits
    if [ -z "$TEMP_INT" ]; then TEMP_INT=0; fi
    if [ "$TEMP_INT" -ge 70 ]; then
        check_fail "Temperature: ${TEMP_C}°C (THROTTLING LIKELY — fix cooling before testing)"
    elif [ "$TEMP_INT" -ge 60 ]; then
        check_warn "Temperature: ${TEMP_C}°C (warm — check cooling)"
    else
        check_pass "Temperature: ${TEMP_C}°C"
    fi
fi

# ---------------------------------------------------------------------------
# 4. Memory
# ---------------------------------------------------------------------------
header "Memory"
MEM_TOTAL=$(get_val MEM_TOTAL)
MEM_AVAIL=$(get_val MEM_AVAIL)
if [ -n "$MEM_TOTAL" ] && [ -n "$MEM_AVAIL" ]; then
    if [ "$MEM_AVAIL" -lt 512 ]; then
        check_warn "Memory: ${MEM_AVAIL} MB available / ${MEM_TOTAL} MB total (low — close other processes)"
    else
        check_pass "Memory: ${MEM_AVAIL} MB available / ${MEM_TOTAL} MB total"
    fi
else
    check_fail "Could not read memory info"
fi

# ---------------------------------------------------------------------------
# 5. NVMe Mount
# ---------------------------------------------------------------------------
header "NVMe Storage"
NVME_MOUNTED=$(get_val NVME_MOUNTED)
SYMLINK_OK=$(get_val SYMLINK)

if [ "$NVME_MOUNTED" = "yes" ]; then
    check_pass "NVMe mounted at /mnt/nvme"
else
    check_fail "NVMe NOT MOUNTED at /mnt/nvme — databases will fail"
fi

if [ "$SYMLINK_OK" = "yes" ]; then
    check_pass "FHS symlink /var/lib/homesynapse exists"
else
    check_fail "FHS symlink /var/lib/homesynapse missing"
fi

# ---------------------------------------------------------------------------
# 6. NVMe Disk Space
# ---------------------------------------------------------------------------
DISK_USE=$(get_val DISK_USE)
DISK_AVAIL=$(get_val DISK_AVAIL)
DISK_TOTAL=$(get_val DISK_TOTAL)

if [ "$DISK_USE" != "UNKNOWN" ] && [ -n "$DISK_USE" ]; then
    if [ "$DISK_USE" -ge 90 ]; then
        check_fail "Disk usage ${DISK_USE}% — critical, run pi-clean.sh (${DISK_AVAIL} free / ${DISK_TOTAL} total)"
    elif [ "$DISK_USE" -ge 80 ]; then
        check_warn "Disk usage ${DISK_USE}% — consider cleanup (${DISK_AVAIL} free / ${DISK_TOTAL} total)"
    else
        check_pass "Disk usage ${DISK_USE}% (${DISK_AVAIL} free / ${DISK_TOTAL} total)"
    fi
else
    if [ "$NVME_MOUNTED" = "no" ]; then
        check_fail "Disk space: cannot check (NVMe not mounted)"
    else
        check_warn "Disk space: could not parse usage"
    fi
fi

# ---------------------------------------------------------------------------
# 7. Java Version
# ---------------------------------------------------------------------------
header "Java"
JAVA_VERSION=$(get_val JAVA_VERSION)
JAVA_CORRETTO=$(get_val JAVA_CORRETTO)
if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -qi "error\|not found"; then
    check_fail "Java not found. Is Corretto 21 installed?"
else
    # Corretto's first line says "openjdk version"; the word "Corretto" appears
    # on the second line.  We check the full java -version output remotely and
    # pass a match-count so the local side doesn't depend on line-1 wording.
    if echo "$JAVA_VERSION" | grep -q "21" && [ "${JAVA_CORRETTO:-0}" -gt 0 ]; then
        check_pass "Java: ${JAVA_VERSION} (Corretto)"
    elif echo "$JAVA_VERSION" | grep -q "21"; then
        check_warn "Java 21 found but not Corretto: ${JAVA_VERSION}"
    else
        check_fail "Wrong Java version: ${JAVA_VERSION} (expected Corretto 21)"
    fi
fi

# ---------------------------------------------------------------------------
# 8. Tailscale Status
# ---------------------------------------------------------------------------
header "Network"
TAILSCALE_STATUS=$(get_val TAILSCALE)
if echo "$TAILSCALE_STATUS" | grep -Eq '"Online": ?true'; then
    check_pass "Tailscale: online"
else
    check_warn "Tailscale: status unclear (SSH works, but mesh health check returned: ${TAILSCALE_STATUS})"
fi

# ---------------------------------------------------------------------------
# 9. Uptime & Load
# ---------------------------------------------------------------------------
header "Uptime"
UPTIME_LINE=$(get_val UPTIME)
info "${UPTIME_LINE}"

# ---------------------------------------------------------------------------
# 9a. Throttle Flags (benchmark validity — temperature alone is NOT sufficient)
# ---------------------------------------------------------------------------
header "Throttle Flags"
THROTTLED_VAL=$(get_val THROTTLED)
if [ "$THROTTLED_VAL" = "UNAVAILABLE" ] || [ -z "$THROTTLED_VAL" ]; then
    check_warn "Throttle bitfield unavailable (vcgencmd missing?) — rely on temperature only"
elif [ "$THROTTLED_VAL" = "0x0" ]; then
    check_pass "Throttle flags: 0x0 (no under-voltage/throttling now or since boot)"
else
    # Bits: 0=under-volt NOW, 1=freq-capped NOW, 2=throttled NOW, 3=soft-temp NOW;
    # 16-19 = the same conditions SINCE BOOT (history).
    check_fail "Throttle flags: ${THROTTLED_VAL} — non-zero. Benchmark numbers are INVALID under throttle/under-voltage. Check PSU/cooling; reboot to clear history bits before benching."
fi

# ---------------------------------------------------------------------------
# 9b. Clock Sync (event-sourced timestamps + bench timing)
# ---------------------------------------------------------------------------
header "Clock"
NTP_SYNC=$(get_val NTP_SYNC)
if [ "$NTP_SYNC" = "yes" ]; then
    check_pass "Clock: NTP-synchronized"
else
    check_warn "Clock: NTP sync = '${NTP_SYNC}' — verify timedatectl before timestamp-sensitive runs"
fi

# ---------------------------------------------------------------------------
# 9c. NVMe Mount Options (noatime is the documented requirement)
# ---------------------------------------------------------------------------
header "NVMe Mount Options"
NVME_OPTS=$(get_val NVME_OPTS)
if [ "$NVME_OPTS" = "UNKNOWN" ] || [ -z "$NVME_OPTS" ]; then
    check_warn "NVMe mount options unreadable"
elif echo "$NVME_OPTS" | grep -q "noatime"; then
    check_pass "NVMe options: ${NVME_OPTS}"
else
    check_warn "NVMe mounted WITHOUT noatime (${NVME_OPTS}) — write-amplification + bench skew; fix fstab"
fi

# ---------------------------------------------------------------------------
# 9d. Swap (swapping during a bench invalidates numbers)
# ---------------------------------------------------------------------------
header "Swap"
SWAP_TOTAL=$(get_val SWAP_TOTAL)
SWAP_USED=$(get_val SWAP_USED)
if [ -n "$SWAP_USED" ] && [ "$SWAP_USED" -gt 64 ] 2>/dev/null; then
    check_warn "Swap in use: ${SWAP_USED} MB of ${SWAP_TOTAL} MB — memory pressure; investigate before benching"
else
    check_pass "Swap: ${SWAP_USED:-0} MB used of ${SWAP_TOTAL:-0} MB"
fi

# ---------------------------------------------------------------------------
# 9e. On-Pi Repo State (PI_PROJECT_DIR)
# ---------------------------------------------------------------------------
header "Repo (~/${PI_PROJECT_DIR})"
REPO_PRESENT=$(get_val REPO_PRESENT)
if [ "$REPO_PRESENT" = "yes" ]; then
    REPO_HEAD=$(get_val REPO_HEAD)
    REPO_DIRTY=$(get_val REPO_DIRTY)
    info "HEAD: ${REPO_HEAD}"
    if [ "${REPO_DIRTY:-0}" -gt 0 ] 2>/dev/null; then
        check_warn "Repo present but DIRTY (${REPO_DIRTY} modified/untracked) — reconcile before deploying"
    else
        check_pass "Repo present, clean"
    fi
else
    check_warn "No repo at ~/${PI_PROJECT_DIR} (deploy flows may use /opt/homesynapse-tests instead — fine if intentional)"
fi

# ---------------------------------------------------------------------------
# 9f. Pending Reboot
# ---------------------------------------------------------------------------
REBOOT_REQUIRED=$(get_val REBOOT_REQUIRED)
if [ "$REBOOT_REQUIRED" = "yes" ]; then
    check_warn "OS reports reboot-required (pending kernel/firmware update) — reboot before long runs"
fi

# ---------------------------------------------------------------------------
# 10. FHS Directory Integrity
# ---------------------------------------------------------------------------
header "FHS Directories"
FHS_COUNT=$(get_val FHS_COUNT)
FHS_MISSING=$(get_val FHS_MISSING)

if [ "$FHS_COUNT" = "6" ]; then
    check_pass "FHS directories: 6/6 present"
else
    check_warn "FHS directories: ${FHS_COUNT}/6 present"
    if [ -n "$FHS_MISSING" ]; then
        warn "Missing:${FHS_MISSING}"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
printf "\n"
if [ "$FAILURES" -eq 0 ]; then
    printf "${BOLD}${GREEN}═══════════════════════════════════════${NC}\n"
    printf "${BOLD}${GREEN}  Pi Health: ALL CHECKS PASSED ✓${NC}\n"
    printf "${BOLD}${GREEN}═══════════════════════════════════════${NC}\n"
    # Machine-parseable summary (agents parse pasted output on this line):
    echo "READINESS:PASS"
    exit 0
else
    printf "${BOLD}${RED}═══════════════════════════════════════${NC}\n"
    printf "${BOLD}${RED}  Pi Health: ${FAILURES} CHECK(S) FAILED ✗${NC}\n"
    printf "${BOLD}${RED}═══════════════════════════════════════${NC}\n"
    # Machine-parseable summary (agents parse pasted output on this line):
    echo "READINESS:FAIL:${FAILURES}"
    exit 1
fi
