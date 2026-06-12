# Script Output Conventions

**Status: DRAFT — PM-authored 2026-06-12 from the R16 research cycle (REC-186/187 + REC-201–209; merged disposition in the hivemind: `context/planning/2026-06-12_R16_output-contract_merged-disposition.md`). Gate-free: applies to `scripts/` only — no design-doc or amendment machinery involved. Nick ratifies by commit.**

Every HomeSynapse script serves three consumers at once: a human at a TTY, a pipeline, and an LLM agent reading *pasted* output. The organizing principle (R16-B, primary-sourced): **an agent reading a paste cannot see the exit code, so the verdict must be in the text.** These conventions codify the shipped `pi-health.sh` idiom — independently assessed best-in-class in both R16 registers — and fix its one confirmed defect (rule 4).

Reference implementation: `scripts/dev/pi-health.sh` (the `READINESS:` line, the `KEY:value` + `get_val()` pattern, the TTY-gated severity tags). Additional in-house precedents: `scripts/pi4-validation.sh` (`--dry-run`, env-var config, `-h|--help`, remote exit-code propagation) and the OQ-15-2 bench harness (`RESULT:`/`FLAG:` data lines).

---

## 1. The verdict line (machine summary)

Every script that performs checks or produces a pass/fail outcome MUST emit, as its **final stdout line**, a single line-anchored, color-free verdict:

```
<VERDICT_KEY>:PASS
<VERDICT_KEY>:FAIL:<detail>
```

- `<detail>` is a short machine token (a count, a reason key) — never prose with newlines.
- The line is emitted on **every** exit path (pass and fail), position-last, so the tail of any paste carries the verdict.
- Registered verdict keys: `READINESS` (environment/health checks — pi-health.sh). New scripts register their key here.
- Prior art: TAP's line-anchored `^ok`/`^not ok`; the rationale is paste-blindness to `$?` (rule 7).

## 2. The machine format is an API (porcelain rule)

Once a key is greppable, its grammar is a contract. Machine lines (verdict lines, `KEY:value` lines) are **stable, color-free, and configuration-independent** — like `git status --porcelain`: guaranteed not to change in a backwards-incompatible way. A breaking grammar change requires a NEW key name (or an explicit format-version line), never a silent mutation. Deprecate, don't rename.

## 3. Labeled `KEY:value` data lines

One datum per line, grep-anchored at start of line:

```
HOSTNAME:hs-dev-1
MEM_AVAIL:7124
THROTTLED:0x0
```

- Keys: `UPPERCASE_SNAKE`, charset `[A-Z0-9_]`, start with a letter, **no colon in keys**.
- Values MAY contain colons — parsers MUST split on the first colon only (`cut -d: -f2-`, as `get_val()` does).
- Multi-field data lines (e.g. the bench harness `RESULT:warm:<file>:<size>:p50_us=…`) are permitted when the field order is documented in the emitting script's header; the first token is still the grep key.

## 4. Stream discipline: diagnostics → stderr, machine output → stdout  ⚠ RETROFIT

Human messaging (`info`/`ok`/`warn`/`fail`/`header` lines, progress narration) goes to **stderr**. Machine output (verdict line, `KEY:value` data) goes to **stdout**. (Google Shell Style Guide; clig.dev.)

- **Why nothing is lost:** a terminal paste — the primary agent workflow — interleaves both streams, so agents still see diagnostics; a pipe gets a clean machine stream instead of noise.
- **Confirmed defect (R16-B REC-204, verified at `01841ba`): retrofit list** — `scripts/dev/pi-health.sh`, `scripts/pi4-validation.sh` (zero `>&2` in either; all helpers print to stdout), and the OQ-15-2 bench driver. Fix: route the helper functions through `>&2`; keep `KEY:value` + the verdict line on stdout.

## 5. Color: never the carrier of meaning

- Semantics live in the text tags — `[OK]` / `[WARN]` / `[FAIL]` / `[INFO]` survive any color stripping (WCAG SC 1.4.1: color is never the only means of conveying information).
- Color is TTY-gated AND `NO_COLOR`-gated: `if [ -t 1 ] && [ -z "${NO_COLOR}" ]; then …colors… else …empty… fi` (no-color.org). The `NO_COLOR` half is a small retrofit to the existing `[ -t 1 ]` gate.
- No spinners, carriage-return tricks, or ANSI cursor movement in non-TTY output, ever.

## 6. Bounded output; the tail carries the verdict

Output destined for agent consumption is a context-window expense (cf. Anthropic's 25,000-token default tool-response cap). Rules: no dumping unbounded remote blobs (excerpt + count instead); no multi-thousand-character single lines (they defeat line-based pagination — the in-house token-economics law); the verdict is in the final lines of any run, so a tail excerpt is always rulable.

## 7. Exit codes

`0` = pass · `1` = fail · `2` = usage error. Scripts wrapping remote commands propagate the remote code (`exit "${GRADLE_EXIT_CODE}"` — pi4-validation.sh). The exit code and the verdict line MUST agree; the verdict line exists because a pasted transcript carries no `$?`.

## 8. CLI ergonomics floor

Every script: `-h|--help` (usage, env vars with defaults, examples) · `--dry-run` where the script mutates anything (print the plan, do nothing) · configuration via env vars with visible defaults (`PI_HOST="${PI_HOST:-hs-dev-1}"`) · **no interactive prompts when stdin is not a TTY** (`[ -t 0 ]` guard; fail with a clear message instead).

## 9. Optional explicit machine streams (`--kv`, `--json`)

A script MAY offer `--kv`/`--json` for richer machine payloads. Machine lines per rules 1–3 remain **unconditional** — never gate content on TTY detection (auto-switching formats breaks the porcelain rule; only *decoration* may vary with the TTY). Add `--json` only when a real consumer exists.

---

*Provenance: R16-A (market/human factors — WCAG, NO_COLOR, clig.dev/gh/Heroku CLI class, smart-home platform pain corpus) and R16-B (engineering — TAP, git porcelain, Google Shell Style Guide, JSON Lines, systemd journal, Anthropic agent-tooling guidance). Full citations in the returns, archived at `homesynapse-core-docs/research/returns/2026-06-12_Research_16*.md`.*
