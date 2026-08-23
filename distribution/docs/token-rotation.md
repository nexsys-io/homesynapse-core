# Token rotation — the operator path (R-6 / R-8, 2026-08-22)

Companion to `../README.md` and `pairing-wizard-seam.md`. This file is the procedure of
record for rotating, revoking, minting, and inspecting API tokens on a HomeSynapse Core
host. It closes F-S5 (2026-08-20: the mechanism existed in code — "rotation is mint-new +
revoke-old", the store's own javadoc — but nothing outside the JVM could drive it).

## What the pairing token is, and where it lives

Every API request carries `Authorization: Bearer <token>` (INV-SE-02). The token is a
random 256-bit value, URL-safe Base64 (43 characters), shown once. Two files under the
config directory are involved, and they are NOT the same thing:

| File | What it holds | Who writes it |
|---|---|---|
| `config/api_tokens` | **the store**: one row per token — the public key id, the SHA-256 **hash** of the token, the display name, timestamps, scopes, site, and a `revoked` flag. Never a raw token. Revoked rows stay as history. | the running service, and ONLY the running service |
| `config/initial_api_token` | **the delivery artifact**: the raw token minted on the very first start, written once so the operator can read it. | the service, at the first-run mint and at `rotate`/`mint` |

Source of record: `api/rest-api/src/main/java/com/homesynapse/api/rest/OpaqueTokenStore.java`
(the class javadoc; `ensureInitialToken()` mints **only when the store is EMPTY**).

**Why deleting the artifact neither revokes nor re-mints (the F-S1 lesson, 2026-08-20).**
The artifact is delivery, not identity. `rm config/initial_api_token` removes the one
readable copy of the secret; the hash stays in the store and the token stays VALID. The
next start sees a non-empty store and mints nothing. An exposed token is retired only by
revoking its hash — which is what `rotate` and `revoke` do.

**Why the store is never edited by hand.** The service rewrites `api_tokens` whole under
its own lock. A second process editing the file offline (or two processes at once) is a
lost-update race by construction. The operator path therefore never touches the store: it
hands the service a *request* that the service applies at its next start.

## The operator path on a packaged install

The helper `homesynapse-token` (installed to `/usr/bin` by the `.deb` and by
`distribution/install/install.sh`) carries every verb. The no-arg form is unchanged.

| Command | What it does | Restart? |
|---|---|---|
| `sudo homesynapse-token` | prints the token in `config/initial_api_token` (the original verb) | no |
| `sudo homesynapse-token status` | lists every stored token: key id, name, created, expires, scopes, site, state (`active` / `revoked` / `expired`) and the counts. Read-only — runs the runtime's `homesynapse token status` **as the service user** (the store is `homesynapse`-owned 0600 inside a 0700 dir; `runuser` when already root, `sudo -u` otherwise). Never prints a token or a hash. | no |
| `sudo homesynapse-token rotate` | **all-sessions rotation** — the remediation for a disclosed credential: revokes EVERY active token and mints ONE new full-access token, delivered to `config/initial_api_token`. Every client re-pairs with the new token. | yes |
| `sudo homesynapse-token revoke <keyId>` | revokes ONE token by its public key id (`status` lists them). Leaves the others alone. **Refused when it would revoke the LAST active full-access token** (R-H2, R-9 — the self-lockout class): the summary WARN reports `revoked=0` with a `skipped` entry `line N: revoke <keyId>: refused — the last active full-access token (use rotate)`; use `rotate`. | yes |
| `sudo homesynapse-token mint <name>` | mints ONE additional full-access token labelled `<name>`, delivered to `config/initial_api_token`. | yes |

**The mechanism behind the three mutating verbs.** Each appends one line (`rotate` ·
`revoke <keyId>` · `mint <name>`) to `/var/lib/homesynapse/config/token_ops.request`,
created owner `homesynapse` mode 0600 (the service user must be able to READ it; deleting
it needs write on the config dir, which the service user owns), then runs
`systemctl restart homesynapse.service`. The restart blocks until the unit's
`ExecStartPost` health probe passes, so when the helper returns the request has already
been applied. At startup the service, immediately after the first-run mint check, reads the
request, **deletes it BEFORE executing a single verb** (a crash mid-batch can never replay
it), applies each line, and logs ONE WARN:

```
token operator request applied: rotated=1 revoked=N minted=0 skipped=[] — a minted token, if any, is at /var/lib/homesynapse/config/initial_api_token
```

The line carries counts and the artifact PATH, never a token — `skipped` entries are
line-numbered reasons (`line 3: unknown verb (line not echoed)`), so a raw token pasted
where a key id belongs never reaches the journal. A request the service cannot read (wrong
owner/mode) or cannot delete executes NOTHING and is logged as such — the helper then
reports `Request NOT consumed` and exits 1; fix the ownership and restart. The service never
refuses to boot over a bad request.

**Read the new token on the host terminal only:** `sudo homesynapse-token`. Then re-pair
each client (the dashboard's token prompt, `curl -H "Authorization: Bearer …"`, the bench's
`api_token` reader — whatever consumed the old one).

### History — the readiness-probe caveat (R-6 → R-9)

From R-6 (2026-08-22, the first cut of this page) until R-9 the unit's `ExecStartPost` probe
authenticated with the token in `config/initial_api_token`, so a bare `revoke` of that key, a
deleted artifact, or a `rotate` whose restart failed each ended in a start-limited unit (the
R-6/R-8 audit's H-1). Since R-9 the unit probes the unauthenticated loopback `/health`
(`--health-path /health`) and reads NO token: deleting the artifact after pairing is lawful
again, the helper no longer touches it, and a `mint`/`revoke` batch in one restart is clean —
`printf 'mint ops\nrevoke <oldKey>\n' > /var/lib/homesynapse/config/token_ops.request && chown homesynapse:homesynapse /var/lib/homesynapse/config/token_ops.request && chmod 0600 /var/lib/homesynapse/config/token_ops.request && systemctl restart homesynapse.service`
(lines execute in order; `mint` first so the `revoke` is never the last full-access token).

## The bench / dev recipe (no helper)

On the bench the ssh user IS the service user and there is no systemd unit, so a bare
redirect is lawful and `bench.sh restart` is the restart. `HOMESYNAPSE_HOME` must be set in
YOUR shell — `bench.sh` exports it only for the process it launches (`tools/bench.sh:6`) —
so the recipe starts by setting it (`~/hs-bench` on the bench; your own dev home for a
`~/.homesynapse-dev`-style launcher):

```
export HOMESYNAPSE_HOME=~/hs-bench   # the bench; your own dev home elsewhere
printf 'rotate\n' > "$HOMESYNAPSE_HOME/config/token_ops.request"   # or: revoke <keyId> · mint <name>
~/bench.sh restart
grep -c "token operator request applied" "$(~/bench.sh log)"       # expect 1 on this boot
cat "$HOMESYNAPSE_HOME/config/initial_api_token"                    # READ ON THE HOST TERMINAL ONLY
```

After the restart the request file is gone, `api_tokens` + `initial_api_token` carry fresh
mtimes (both `-rw-------`), and `bench.sh api_token` (which reads the artifact) hands the
runner the NEW token — the bench's own readers re-pair for free. Inspect with the runtime
directly:

```
HOMESYNAPSE_HOME=~/hs-bench ~/homesynapse-core/app/homesynapse-app/build/install/homesynapse-app/bin/homesynapse-app token status
```

(`token status` is read-only: it loads the store, prints the table, exits. It creates no
directory and writes no file.)

## The emergency store reset (last resort — loses history)

If the request path itself is unavailable (for example the service user cannot write the
config dir), the blunt reset from the 2026-08-20 sitting record still works:

```
mv config/api_tokens config/api_tokens.rotated-$(date +%F)
<restart the service>
cat config/initial_api_token     # the empty store triggered the first-run mint
```

Every old token dies with its hash (the moved file keeps them for forensics — delete
nothing). This also throws away the revocation history and every extra token that was
minted; `rotate` does neither. Use it only when `rotate` cannot run.

## Credential hygiene

- **Read tokens on the host terminal only.** Never paste a token into chat, a ticket, a
  screenshot caption, or a commit.
- **Screenshots of request headers are token-carriers.** A DevTools Headers tab shows the
  full `Authorization: Bearer …` line — crop or mask it before sharing (the F-S1 incident).
  When in doubt, `rotate` afterwards; it costs one re-pair.
- **The artifact is a secret.** It is `homesynapse`-owned 0600 (the service writes both
  secret-bearing files owner-only as of R-6) inside a 0700 config dir. It may be deleted
  after pairing — lawful on a packaged host since R-9: the unit's probe reads `/health`,
  never the artifact (see the History note above) — and `rotate` / `mint` re-create it.
- **Logs never carry a token** except the ruled first-run WARN (`Minted the initial
  HomeSynapse API token … Token: …`) — the request path logs counts and the artifact path
  only, and the HTTP admin path logs key ids only.
- **`status` is safe to share:** it prints key ids (public), names, and claims — no hash, no
  token.

## The `/internal/tokens` API (token-holders; the pairing wizard's hand-off)

Behind the same bearer auth as every route (INV-SE-02), outside the readiness gate, and
**only for full-access tokens** (a scoped token is 403 on all three). RFC 9457
`application/problem+json` on every non-2xx. No `meta.viewPosition` / ETag — these are not
projection reads. `Cache-Control: no-store` on every response.

| Route | Body / params | Response |
|---|---|---|
| `GET /internal/tokens` | — | `200 { "data": { "tokens": [ { "keyId", "displayName", "createdAt", "expiresAt" \| null, "scopes", "siteId" \| null, "revoked" } ] }, "meta": { "timestamp" } }` — hashes and raw tokens never appear |
| `POST /internal/tokens` | `{ "displayName": "…", "scopes"?: ["*"], "siteId"?: "…" }` | `201 { "data": { "keyId", "token" }, "meta": { "timestamp" } }` — the raw token is returned ONCE; `displayName` blank/missing → 400; `scopes` defaults to `["*"]` |
| `DELETE /internal/tokens/{keyId}` | — | `204` when an active token was revoked; `404` otherwise; `409` `token-revoke-refused` when the key is the LAST active full-access token (R-H2 — nothing mutated, no audit line; `rotate` instead). Self-revocation is allowed while another full-access token is active — the response completes, every later request with that token is 403 |

Every mint/revoke writes one INFO audit line to the journal:
`token admin: actor=<callerKeyId> verb=<mint|revoke> target=<keyId>` — key ids only.

The API needs a valid full-access token to present. The case where you do not have one you
trust — the disclosed credential — is exactly what the request file is for.
