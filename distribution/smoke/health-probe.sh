#!/bin/sh
# health-probe.sh — loopback readiness probe for HomeSynapse Core.
#
# Self-contained on purpose: this ships INSIDE the runtime image at
# /opt/homesynapse/libexec/health-probe.sh and runs where the distribution/ tree
# is NOT present, so it sources nothing and hard-codes safe defaults.
#
# Readiness model (E3 closed at R-9: /health is UNauthenticated on loopback):
#   HTTP 200  → the state projection is LIVE     → exit 0
#   HTTP 503  → up but projection not ready      → keep waiting (with --wait)
#   HTTP 401/403 (only once a token is loaded)   → auth/config fault → exit 3
#   no answer (000 / refused)                    → not up yet         → keep waiting
#
# The unit + install/update probes pass --health-path /health (no token read);
# run-smoke.sh check 3 keeps the authed default to prove the token validates.
#
# Usage:
#   health-probe.sh [--wait] [--timeout N] [--host H] [--port P]
#                   [--health-path PATH] [--token-file FILE | --token VALUE]
set -eu

HOST="127.0.0.1"
PORT="7070"
HEALTH_PATH="/api/v1/entities"
TOKEN_FILE="/var/lib/homesynapse/config/initial_api_token"
TOKEN=""
WAIT=0
TIMEOUT=90
INTERVAL=2

while [ $# -gt 0 ]; do
    case "$1" in
        --wait) WAIT=1 ;;
        --timeout) TIMEOUT="$2"; shift ;;
        --host) HOST="$2"; shift ;;
        --port) PORT="$2"; shift ;;
        --health-path) HEALTH_PATH="$2"; shift ;;
        --token-file) TOKEN_FILE="$2"; shift ;;
        --token) TOKEN="$2"; shift ;;
        --interval) INTERVAL="$2"; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) printf 'health-probe: unknown arg: %s\n' "$1" >&2; exit 2 ;;
    esac
    shift
done

URL="http://${HOST}:${PORT}${HEALTH_PATH}"
# An unauthenticated health path (anything not under /api or /internal) needs no token.
NEEDS_AUTH=1
case "${HEALTH_PATH}" in
    /api/*|/internal/*) NEEDS_AUTH=1 ;;
    *) NEEDS_AUTH=0 ;;
esac

log() { printf '[health-probe] %s\n' "$*" >&2; }

# One HTTP probe → prints the numeric status code (000 if no connection).
# NOTE: no `-f`/--fail — we WANT the real 4xx/5xx code, not a curl error exit.
# With -f, curl exits non-zero on 503/401 and the code would be corrupted.
http_code() {
    _auth="$1"; _code=""
    if command -v curl >/dev/null 2>&1; then
        # `|| true` keeps a curl failure exit (e.g. 7 = connection refused) from
        # tripping `set -e` in the single-shot path; curl still emits the 000 code.
        if [ -n "${_auth}" ]; then
            _code="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' \
                 -H "Authorization: Bearer ${_auth}" "${URL}" 2>/dev/null || true)"
        else
            _code="$(curl -sS -o /dev/null -m 5 -w '%{http_code}' "${URL}" 2>/dev/null || true)"
        fi
        [ -n "${_code}" ] && printf '%s' "${_code}" || printf '000'
    elif command -v wget >/dev/null 2>&1; then
        # wget: map success→200, server-error→that code is hard to read; best effort.
        if [ -n "${_auth}" ]; then
            wget -q -O /dev/null --timeout=5 --header="Authorization: Bearer ${_auth}" "${URL}" \
                && printf '200' || printf '000'
        else
            wget -q -O /dev/null --timeout=5 "${URL}" && printf '200' || printf '000'
        fi
    else
        log "neither curl nor wget available"; printf '000'
    fi
}

load_token() {
    [ "${NEEDS_AUTH}" -eq 1 ] || return 0
    [ -n "${TOKEN}" ] && return 0
    if [ -r "${TOKEN_FILE}" ]; then
        TOKEN="$(tr -d ' \r\n' < "${TOKEN_FILE}")"
        [ -n "${TOKEN}" ] && return 0
    fi
    return 1
}

probe_once() {
    if ! load_token; then
        log "token not yet available at ${TOKEN_FILE}"
        return 1
    fi
    code="$(http_code "${TOKEN}")"
    case "${code}" in
        200) log "ready (200) at ${URL}"; return 0 ;;
        503) log "up, not ready yet (503)"; return 1 ;;
        401|403) log "auth rejected (${code}) — token/config fault"; return 3 ;;
        000) log "no connection yet"; return 1 ;;
        *)   log "unexpected status ${code}"; return 1 ;;
    esac
}

if [ "${WAIT}" -eq 0 ]; then
    probe_once; exit $?
fi

log "waiting up to ${TIMEOUT}s for readiness at ${URL}"
elapsed=0
while [ "${elapsed}" -lt "${TIMEOUT}" ]; do
    set +e; probe_once; rc=$?; set -e
    [ "${rc}" -eq 0 ] && exit 0
    [ "${rc}" -eq 3 ] && exit 3       # hard auth fault — don't burn the whole timeout
    sleep "${INTERVAL}"
    elapsed=$((elapsed + INTERVAL))
done
log "TIMEOUT after ${TIMEOUT}s — service did not become ready"
exit 1
