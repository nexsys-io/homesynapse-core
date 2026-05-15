# Coder Handoff

Authoritative record of the Coder's latest work-unit closeout. The PM reads this between sessions to verify Work Unit Completion Protocol (WUCP) Phase 1 status, identify the next work unit, and track any deferred build gates as open risks.

---

## ⚠ Deferred Build Gate (current work unit)

**Status:** `./gradlew check` was NOT run in-session for the AMD-38/39 finalization + DeploymentProfile correction work unit.

**Reason:** Nick explicitly owns the compile/check gate for this work unit (brief, §Out of Scope: "Running any gradle commands, compilation, or builds — Nick owns the compile gate"). The change is narrow (one Java file edit changing three constants and Javadoc; documentation-only edits elsewhere) and the prior session's sandbox build-environment limitations remain in effect.

**Commands Nick must run before this work unit is considered fully verified:**

```bash
cd ~/Desktop/Code/ClaudeFolder/homesynapse-core

# Compile gate for the one Java file changed
./gradlew :core:persistence:compileJava

# Spotless check (zero-warning -Werror + copyright header)
./gradlew :core:persistence:spotlessCheck

# Full build — verify no regressions in dependent modules (state-store
# consumes DeploymentProfile via FixedCheckpointPolicy / PersistenceConfig wiring)
./gradlew check
```

**Against commit:** the commit that changes `journalSizeLimitBytes` from profile-specific (32/64/256 MB) to uniform `6_144_000L` (6 MB) in `core/persistence/src/main/java/com/homesynapse/persistence/DeploymentProfile.java`.

**Risk if not run:** none of the changes touch executable code paths (no method bodies, no signatures, no new types), so the compile risk is low — but the convention plugin's `-Xlint:all -Werror` setting can still fail on Javadoc problems (broken `@code` blocks, unclosed `{@link}`, etc.), and Spotless can fail if the copyright header drifts. The full `check` task also runs any ArchUnit and dependency-graph rules that might react to constant changes (extremely unlikely here, but the safety net is cheap).

### Prior deferred gate — RESOLVED

The D1 spike (work unit closed 2026-05-15) was previously flagged here as a Deferred Build Gate. **Resolved**: Nick ran `:spike:wal-validation:compileJava`, `:spike:wal-validation:spotlessCheck`, and `:spike:wal-validation:runD1` on 2026-05-15. All three gates passed and the spike produced the empirical results that drove AMD-38 APPLIED / AMD-39 WITHDRAWN. The deferred-gate flag for D1 is cleared.

---

## Most Recent Work Unit

**Work Unit:** AMD-38 finalization, AMD-39 withdrawal, and DeploymentProfile correction
**Phase:** Post-spike cleanup (documentation + minimal Java constant fix)
**Completed:** 2026-05-15
**Subsystem:** Persistence Layer + Governance
**Driver:** D1 WAL Pathology Validation Spike results (2026-05-15)

### Summary

Promoted AMD-38 (checkpoint policy: 200 events / 2 s) from DRAFT to APPLIED, withdrew AMD-39 (journal_size_limit raise to 64 MB), and corrected `DeploymentProfile.{STUDIO, HOME, PERFORMANCE}.journalSizeLimitBytes()` to the uniform LTD-03 value of `6_144_000L` (6 MB) that the D1 spike empirically validated. Updated the persistence `MODULE_CONTEXT.md` so its DeploymentProfile type-inventory row and AMD-38/39 gotcha reflect the resolved state.

### Files Modified

| Path | Change |
|---|---|
| `homesynapse-core-docs/design/amendments/AMD-38_Checkpoint_Policy_Revision.md` | Status DRAFT → APPLIED; added "Date applied: 2026-05-15"; replaced Rationale's "provisional pending D1" language with the empirical D1 result narrative; rewrote Implementation Impact to remove AMD-39 cross-reference and reflect the APPLIED state; replaced the Validation Gate section with the resolved per-run results and gate outcome. Source-list extended to cite the D1 spike. |
| `homesynapse-core-docs/design/amendments/AMD-39_Journal_Size_Limit_Revision.md` | Status DRAFT → WITHDRAWN; added "Date withdrawn: 2026-05-15"; added a WITHDRAWN banner directly under the metadata so first-glance readers see the document is historical; replaced the Validation Gate section with the RESOLVED-WITHDRAWN narrative including the burst-load caveat. Body of the document (Problem, Change, Rationale, Implementation Impact, Invariant Alignment, Downstream Dependencies) left as historical record per the brief's scope. |
| `homesynapse-core/core/persistence/src/main/java/com/homesynapse/persistence/DeploymentProfile.java` | STUDIO/HOME/PERFORMANCE `journalSizeLimitBytes` constructor argument changed from `33_554_432L` / `67_108_864L` / `268_435_456L` to uniform `6_144_000L`. Enum-level Javadoc rewritten to note the journal-size-limit value is now uniform per LTD-03 with the D1 validation citation, and that AMD-39 was withdrawn. Per-constant Javadoc (STUDIO, HOME, PERFORMANCE) updated so each `journal_size_limit` annotation reads `6144000 (6 MB, LTD-03 validated by D1 spike)`. The `journalSizeLimitBytes()` accessor's Javadoc rewritten to describe the uniform value and D1 citation. `cacheSizeKiB` and `mmapSizeBytes` left untouched. |
| `homesynapse-core/core/persistence/MODULE_CONTEXT.md` | DeploymentProfile type-inventory row: updated all three profiles' `journalSizeLimitBytes` to `6_144_000`; replaced "carries AMD-39 (provisional pending D1 spike)" with the empirical resolution and the AMD-38 cross-reference. AMD-38/39 gotcha: rewritten to reflect APPLIED (AMD-38) + WITHDRAWN (AMD-39) state, noting the Java constant and `DatabaseExecutor.CONNECTION_PRAGMAS` now agree on 6 MB so no further Phase 3 PRAGMA update is required, and clarifying that the active 30 s PASSIVE checkpoint is defense-in-depth not load-bearing under nominal load. |
| `homesynapse-core/docs/handoff/coder-handoff.md` | This file — rewritten for the new work unit. |

### Success Criteria

1. **AMD-38 promoted to APPLIED with empirical evidence cited.** ✓ Status line, date applied, Validation Gate RESOLVED section, and source list all reflect the D1 outcome.
2. **AMD-39 withdrawn with rationale captured.** ✓ Status line, date withdrawn, top-of-document WITHDRAWN banner, and Validation Gate RESOLVED-WITHDRAWN section all present. Burst-load caveat included verbatim from the brief.
3. **DeploymentProfile constants corrected to uniform LTD-03 value.** ✓ All three profiles use `6_144_000L`. cacheSizeKiB and mmapSizeBytes untouched per the brief.
4. **All Javadoc on DeploymentProfile scrubbed of AMD-39 / provisional / pending D1 language.** ✓ Enum-level, per-constant, and accessor Javadoc all updated.
5. **Persistence MODULE_CONTEXT.md reflects the resolved state.** ✓ Both the type-inventory row and the gotcha are updated.

### Deviations

**`[INFO]` Body of AMD-39 (Problem, Change, Rationale, Implementation Impact, etc.) was NOT rewritten.**
  Specified (for AMD-38): "Remove or update any language in the document body that says 'provisional' or 'pending D1' — the values are now empirically validated."
  AMD-39 brief instructions only explicitly required: status header change, date withdrawn, Validation Gate replacement, and the WITHDRAWN banner.
  Implemented for AMD-39: only those four items. The body's "provisional pending the WAL pathology spike (D1, pre-M3 gate)" wording in the Rationale section is preserved.
  Reason: A WITHDRAWN amendment functions as a historical record explaining what was proposed and why; readers see the WITHDRAWN banner at the top and the RESOLVED gate section, and then the body provides the proposal's reasoning at the time it was drafted. Rewriting "provisional" → "would have been" prose risks rewriting history. The brief's instructions for AMD-39 deliberately did not include the body scrub that AMD-38 received, so I followed the brief's narrower scope.
  Impact: None on correctness. AMD-39 is unambiguously marked WITHDRAWN at multiple visual touchpoints.

**`[INFO]` Updated DeploymentProfile type-inventory row in MODULE_CONTEXT.md (not explicitly listed as a required edit).**
  Specified: "Update it to reflect: AMD-38 APPLIED, AMD-39 WITHDRAWN, the D1 spike validated both decisions empirically" — the brief calls this out for "the gotcha."
  Implemented: also updated the DeploymentProfile entry in the §Configuration, Profile, and Maintenance Subscriber type inventory table, because the same row had stale `journalSizeLimitBytes=33_554_432 / 67_108_864 / 268_435_456` values and a "carries AMD-39 (provisional pending D1 spike)" annotation.
  Reason: The type inventory is the canonical "what types exist" reference that future agents read. Leaving it stale would re-introduce the same drift the gotcha is designed to prevent. The scope expansion is minimal (one row, same file already in scope).
  Impact: None other than reducing the chance of future agent confusion.

**`[INFO]` AMD-38 Implementation Impact section updated to remove its forward-reference to AMD-39.**
  Specified: scrub "provisional" / "pending D1" language in the body.
  Implemented: also rewrote the sentence that read "the executor's own PRAGMA settings are governed by LTD-03 (and AMD-39 for `journal_size_limit`)" — since AMD-39 is no longer the governing authority for `journal_size_limit`, the reference is now stale. Updated to "LTD-03. (`journal_size_limit` stays at LTD-03's 6,144,000 — see AMD-39, which was WITHDRAWN on 2026-05-15.)"
  Reason: The brief's "remove or update language that says provisional or pending D1" instruction implicitly covers stale cross-references that conflict with the resolved state.
  Impact: None on correctness; improves clarity for readers cross-referencing the amendments.

**No `[REVIEW]` or `[BLOCKING]` deviations.**

### Constraint Adherence

| Constraint | Status | Notes |
|---|---|---|
| **LTD-03** | Preserved | `journal_size_limit = 6,144,000` is the authoritative value across all profiles. |
| **AMD-37** | Untouched | chain_hash NOT NULL — out of scope. |
| **AMD-38** | Now APPLIED | (200 events, 2 s, 1 s min interval) values locked. |
| **AMD-39** | Now WITHDRAWN | LTD-03 governs `journal_size_limit` unchanged. |
| **Out of Scope discipline** | Followed | No Java file other than `DeploymentProfile.java` was modified. No tests written. No spike code touched. No M3 Phase 3 planning. |

### Pre-existing Code Observations (not changed)

- `DatabaseExecutor.CONNECTION_PRAGMAS` already had `journal_size_limit = 6144000` — the V001-era value. Because AMD-39 is WITHDRAWN, this value is now correctly aligned with `DeploymentProfile.HOME.journalSizeLimitBytes()`. No PRAGMA update is needed in M3 Phase 3 for this knob.
- `PragmaConfig.verify()` in the spike module hardcodes the same 6 MB value. Still correct.
- The D1 spike code is preserved as historical evidence and will not be modified.

### Next Work Unit

**M3 Phase 3 planning and kickoff.**

The M2→M3 bridge is now complete:

- AMD-38 APPLIED (checkpoint policy locked)
- AMD-39 WITHDRAWN (journal_size_limit unchanged)
- AMD-40 APPLIED (per the persistence MODULE_CONTEXT gotcha — retention execution model)
- V003 migration ready (per pre-existing project state)
- All Phase 2 interfaces locked (16 of 19 modules with populated MODULE_CONTEXT.md, 3 scaffold stubs awaiting Phase 3)
- D1 spike has empirically validated the persistence layer's WAL behavior under the M3 reader pattern

The Coder is ready to receive the first M3 Phase 3 coding instruction once the PM has assembled the Phase 3 work unit brief. Likely first targets:

- Production `ProjectionAdvancer` with bounded-window contract (close/reopen read transaction every N rows; the brief should specify the chunk size — D1 used 500)
- `FixedCheckpointPolicy` consumer wiring (apply AMD-38's max_interval/event_threshold to the projection loop)
- Optional `ActiveCheckpointService` issuing `wal_checkpoint(PASSIVE)` at the AMD-38 cadence (defense-in-depth; D1 demonstrated this is redundant under nominal load but cheap and protective under degraded conditions)

---

## WUCP Phase 1 Checklist (AMD-38/39 Finalization Closeout)

- [x]   MODULE_CONTEXT.md updated for: `core/persistence`
- [x]   coder-handoff.md updated (this file)
- [x]   **Deferred build gate flag: YES** (see top section — Nick to run `./gradlew :core:persistence:compileJava`, `:spotlessCheck`, and `./gradlew check`)
- [N/A] coder-lessons.md appended (no new implementation patterns — this is a constants-and-docs cleanup, governance bookkeeping after a validated spike)
- [N/A] Cross-agent note posted (no cross-subsystem implications beyond what the updated MODULE_CONTEXT gotcha already documents)
- Timestamp: 2026-05-15

---
