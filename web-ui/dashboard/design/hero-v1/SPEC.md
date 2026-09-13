<!--
file: web-ui/dashboard/design/hero-v1/SPEC.md
purpose: The explainability hero design specification, v1 — the L1 headline grammar per (RunStatus, leading ActionOutcome) and per NonFiringVerdict, the four empty states, the five confirmation modes, the EXPLAIN-1..9 placeholders, the copy table, accessibility, tokens, the acceptance script, the build rows and the questions for Nick.
audience: Nick (rules on §12 in one batch) · the hub (audits) · the implementing lane (FE-114 / HERO-1b — §11 is its order of work)
state-type: design specification (not shipped, not bundled, touches no source)
status: v1 DRAFT FOR RULING — 2026-09-11 (HERO-1 design lane; baseline core HEAD eabdbb1). Product name appears only as the token {{NAME}}.
-->

# The explainability hero — design specification v1

Ground: `src/lib/api/contract.ts` at `eabdbb1` (`RunStatus` :140, `ActionOutcome` :150, `NonFiringVerdict` :153–:157, `CausalChain` :367–:385 with `cascade` :384, the `resultOutcome` note :346–:353), `src/lib/format.ts` (`causalSentence` :472, `commandVerb` :519–:532), `src/lib/verdicts.ts` (`ActionMode` :285, `MODE_GLYPHS` :298, `actionVerdict` :340), the HERO-0 null census (`context/research/2026-09-06_HERO-0_null-census_v1.1.3_return.md` §1–§3), Doc 08 §3.6 (AMD-97, :275–:295), Doc 16 §3.3 (:130–:144) and INV-SA-01..04 (:262–:265), the DAS reference §1.1 / §2.2 / §2.3. Sample slot values in this document (Hallway Light, Hallway Motion, Evening Lights, 9:42 pm) are fixtures, not claims. Every string a person reads is written here once, in Register C, and is centralised and test-locked by the build (§7, §11).

## §1 What the hero is for

The hero answers three questions a person asks their home, in words they can read aloud and be right about: why did it fire, why didn't it, and did the device actually confirm. It leads with the two halves the log makes durable — the honest command outcome on every action (confirmed · sent, not confirmed · failed) and the fact that the explanation is rebuilt from the permanent activity log and is never deleted (INV-SA-03) — and it keeps "why didn't it fire" a co-equal entry point. The stranger who must understand it has never seen the product, does not know what a run, a trigger or a ULID is, saw a light turn on (or not) and wants to know why. A prosumer buyer reads the same sentence and sees evidence, not decoration. The bar is the stranger test: the person reads the L1 sentence aloud and is right; and every sentence keeps the honesty law — a value the wire did not carry is never shown as if it had, `DISPATCHED` is not delivered, an acknowledgement is not confirmation, and `UNCONFIRMED` is calm.

## §2 Information architecture

Three entry points, one hub. The hub page ("Ask your home why") shows the two questions as peer cards — why did something happen → the recent-runs list → a run page; why didn't something happen → pick an automation → the why-not card — and under them the automation list, each row with both links. The third question, did it actually confirm, is not a separate page: it is the outcome pill on every action step and the tail of every headline, so it is answered wherever a run is shown. A device page links into the run that last changed it; the why-not card links into the run it names. The hub is unchanged in shape from today's `ExplainHubView`; this spec changes its copy only (§7).

Two disclosure levels, and no third. L1 is one plain sentence — the headline — followed by a bounded, linear step chain: the trigger, each condition, each action, the terminal outcome, rendered as an ordered list with a marker (shape) and a label per step. L2 is the technical fact one expand away under the step it belongs to: the trigger type and firing value, the observed state at evaluation, the command and its params, the recorded reason, the recorded outcome. Nothing else appears: no index paths, no ULIDs unless the registry cannot resolve one (then the id appears verbatim with the fact that it is not in this hub's registry — an honest accusation of nothing), no correlation ids, no free graph. A cascade is one line under the chain ("Started by another run" with a link when the parent id is on the wire, and the honest "which one isn't recorded" line when it is not).

The run page order, top to bottom: the headline (L1); the name note when the automation's name is no longer on record; the chain; the cascade line; the "As of" freshness line; the permanence footer. The why-not card order: the verdict pill and the automation name; the L1 sentence; the body; "What would make it run" (and "Watching: {entity}" when the wire names one); "Last checked"; the one link that leads to the run that proves the verdict.

## §3 The L1 headline grammar

The headline follows the run's status and the leading action's outcome, never the command's verb alone — the defect FE-NULL-1 O1 named (`commandVerb(:519)` turns `turn_on` into "turned on" on a SKIPPED run). The leading action is `actions[0]`; "no action" is the arm where `actions[]` is empty, split by the outcome counters into "recorded no steps" (`actionCount` 0) and the silent skip (`actionCount` > 0, `commandCount` 0). A COMPLETED run is device-led: the sentence starts with the device the person noticed. A run that did not complete is automation-led: the frame says what the run did, then one tail sentence says what happened to the leading device — so a skipped or failed run never opens with a device acting.

Slots and their null arms (each arm is a keyed string in §7, so no cell is left to inference): `{Target}` is the target's display name; `targetRef` null → "a device the run didn't name"; a dangling ref on a complete census → "entity {id} (not in this hub's registry)". `{verb}` / `{verbPast}` come from the command (turn_on → turn on / turned on; turn_off; dim → dim / dimmed); a command with no plain verb → run "{command}" / ran "{command}"; command null → act / acted (unreachable in a dispatched cell, defined anyway). `{because}` is "{Trigger} {triggerVerb} at {time}"; `subjectRef` null → "something set it off at {time} (what isn't recorded)"; `firingValue` null (today: always) → the verb "changed". `{Automation}` is `automationName`; null → "An earlier automation". `{time}` is the date-qualified clock time from `matchedAt` (`clockTimeWithDate`); unparseable → "an unrecorded time". Every headline is ≤ 21 words per sentence at the sample slots (§7 measures it).

The mode override: when `actionVerdict()` classifies the leading action as superseded (a DISPATCHED or FAILED outcome whose `resultOutcome` is `superseded`) the cell's clause is replaced by the superseded key, and when it classifies it as expired-restart, by the expired-restart key — a replaced command is an intent change, not a failure, and the headline may not say "failed" for it. The thirty cells:

| # | RunStatus | Leading ActionOutcome | Sentence (sample slots) | Key(s) | Pill / shape / colour role |
|---|---|---|---|---|---|
| 1 | COMPLETED | CONFIRMED | Hallway Light turned on because Hallway Motion detected motion at 9:42 pm. | `explain.headline.completed.confirmed` | Confirmed · check · ok |
| 2 | COMPLETED | DISPATCHED | Hallway Light was asked to turn on because Hallway Motion detected motion at 9:42 pm — no confirmation yet. | `explain.headline.completed.dispatched` | Sent — not settled yet · arrow, dashed · info (provisional) |
| 3 | COMPLETED | UNCONFIRMED | Hallway Light was asked to turn on because Hallway Motion detected motion at 9:42 pm — it never confirmed. | `explain.headline.completed.unconfirmed` | Sent — no reply · clock · warn (mode 1) or Accepted, never confirmed · ack+dots · warn (mode 3) |
| 4 | COMPLETED | FAILED | Hallway Light was asked to turn on because Hallway Motion detected motion at 9:42 pm, but the command failed. | `explain.headline.completed.failed` | Failed · x · error (mode 5); Replaced · swap · neutral (mode 2); Expired at restart · arc · unknown |
| 5 | COMPLETED | SKIPPED | Nothing was sent to Hallway Light when Hallway Motion detected motion at 9:42 pm — that step was skipped. | `explain.headline.completed.skipped` | Skipped · skip · unknown |
| 6 | COMPLETED | — (no action) | Evening Lights ran when Hallway Motion detected motion at 9:42 pm and recorded no steps. | `explain.headline.completed.none` · `explain.headline.completed.silentSkip` | — (terminal pill only) |
| 7 | SKIPPED | CONFIRMED | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. Hallway Light did turn on first, and confirmed it. | `explain.headline.skipped.frame` + `explain.headline.tail.confirmed` | Confirmed · check · ok |
| 8 | SKIPPED | DISPATCHED | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; no confirmation has come back. | `explain.headline.skipped.frame` + `explain.headline.tail.dispatched` | Sent — not settled yet · arrow, dashed · info (provisional) |
| 9 | SKIPPED | UNCONFIRMED | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; it never confirmed. | `explain.headline.skipped.frame` + `explain.headline.tail.unconfirmed` | Sent — no reply · clock · warn (mode 1) or Accepted, never confirmed · ack+dots · warn (mode 3) |
| 10 | SKIPPED | FAILED | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. The command to Hallway Light failed. | `explain.headline.skipped.frame` + `explain.headline.tail.failed` | Failed · x · error (mode 5); Replaced · swap · neutral (mode 2); Expired at restart · arc · unknown |
| 11 | SKIPPED | SKIPPED | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. Nothing was sent to Hallway Light. | `explain.headline.skipped.frame` + `explain.headline.tail.skipped` | Skipped · skip · unknown |
| 12 | SKIPPED | — (no action) | Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. | `explain.headline.skipped.frame` + `explain.headline.tail.none` | — (terminal pill only) |
| 13 | FAILED | CONFIRMED | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. Hallway Light did turn on first, and confirmed it. | `explain.headline.failed.frame` + `explain.headline.tail.confirmed` | Confirmed · check · ok |
| 14 | FAILED | DISPATCHED | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; no confirmation has come back. | `explain.headline.failed.frame` + `explain.headline.tail.dispatched` | Sent — not settled yet · arrow, dashed · info (provisional) |
| 15 | FAILED | UNCONFIRMED | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; it never confirmed. | `explain.headline.failed.frame` + `explain.headline.tail.unconfirmed` | Sent — no reply · clock · warn (mode 1) or Accepted, never confirmed · ack+dots · warn (mode 3) |
| 16 | FAILED | FAILED | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. The command to Hallway Light failed. | `explain.headline.failed.frame` + `explain.headline.tail.failed` | Failed · x · error (mode 5); Replaced · swap · neutral (mode 2); Expired at restart · arc · unknown |
| 17 | FAILED | SKIPPED | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. Nothing was sent to Hallway Light. | `explain.headline.failed.frame` + `explain.headline.tail.skipped` | Skipped · skip · unknown |
| 18 | FAILED | — (no action) | Evening Lights failed part-way when Hallway Motion detected motion at 9:42 pm. | `explain.headline.failed.frame` + `explain.headline.tail.none` | — (terminal pill only) |
| 19 | CANCELLED | CONFIRMED | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. Hallway Light did turn on first, and confirmed it. | `explain.headline.cancelled.frame` + `explain.headline.tail.confirmed` | Confirmed · check · ok |
| 20 | CANCELLED | DISPATCHED | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; no confirmation has come back. | `explain.headline.cancelled.frame` + `explain.headline.tail.dispatched` | Sent — not settled yet · arrow, dashed · info (provisional) |
| 21 | CANCELLED | UNCONFIRMED | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; it never confirmed. | `explain.headline.cancelled.frame` + `explain.headline.tail.unconfirmed` | Sent — no reply · clock · warn (mode 1) or Accepted, never confirmed · ack+dots · warn (mode 3) |
| 22 | CANCELLED | FAILED | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. The command to Hallway Light failed. | `explain.headline.cancelled.frame` + `explain.headline.tail.failed` | Failed · x · error (mode 5); Replaced · swap · neutral (mode 2); Expired at restart · arc · unknown |
| 23 | CANCELLED | SKIPPED | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. Nothing was sent to Hallway Light. | `explain.headline.cancelled.frame` + `explain.headline.tail.skipped` | Skipped · skip · unknown |
| 24 | CANCELLED | — (no action) | Evening Lights was cancelled after Hallway Motion detected motion at 9:42 pm. | `explain.headline.cancelled.frame` + `explain.headline.tail.none` | — (terminal pill only) |
| 25 | INTERRUPTED | CONFIRMED | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. Hallway Light did turn on first, and confirmed it. | `explain.headline.interrupted.frame` + `explain.headline.tail.confirmed` | Confirmed · check · ok |
| 26 | INTERRUPTED | DISPATCHED | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; no confirmation has come back. | `explain.headline.interrupted.frame` + `explain.headline.tail.dispatched` | Sent — not settled yet · arrow, dashed · info (provisional) |
| 27 | INTERRUPTED | UNCONFIRMED | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. Hallway Light was asked to turn on; it never confirmed. | `explain.headline.interrupted.frame` + `explain.headline.tail.unconfirmed` | Sent — no reply · clock · warn (mode 1) or Accepted, never confirmed · ack+dots · warn (mode 3) |
| 28 | INTERRUPTED | FAILED | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. The command to Hallway Light failed. | `explain.headline.interrupted.frame` + `explain.headline.tail.failed` | Failed · x · error (mode 5); Replaced · swap · neutral (mode 2); Expired at restart · arc · unknown |
| 29 | INTERRUPTED | SKIPPED | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. Nothing was sent to Hallway Light. | `explain.headline.interrupted.frame` + `explain.headline.tail.skipped` | Skipped · skip · unknown |
| 30 | INTERRUPTED | — (no action) | Evening Lights was cut off before it finished, after Hallway Motion detected motion at 9:42 pm. | `explain.headline.interrupted.frame` + `explain.headline.tail.none` | — (terminal pill only) |

Rows 2–4, 8–10, 14–16, 20–22 and 26–28 are refined by the mode override above (superseded → `explain.headline.completed.superseded` / `explain.headline.tail.superseded`; expired-restart → `…expiredRestart`). Rows 7–12 (a SKIPPED run whose leading action confirmed, was sent, or failed) are unlikely on today's emitter — a skipped run normally carries skipped actions — but each has a sentence so the renderer never infers.

The "why didn't it" headline, one row per `NonFiringVerdict` plus the two derived states the wire already carries. `{time}` is `lastEvaluation.at` and is nullable (`lastEvaluation` is object-or-null on the observed wire), so every row has a no-time arm.

| # | Verdict / derived state | Sentence (sample slots) | Null arm | Key | Pill · shape · colour role |
|---|---|---|---|---|---|
| N1 | CONDITION_NOT_MET | It was set off at 9:42 pm, but a condition was false, so it didn't act. | It was set off, but a condition was false, so it didn't act. | `whyNot.headline.conditionNotMet` · `.noTime` | A condition was not met · ✕ · warn |
| N2 | NEVER_TRIGGERED, `lastRelevantRunId` null | It hasn't run yet. | — | `whyNot.headline.neverTriggered` | Nothing set it off · ○ · neutral |
| N3 | NEVER_TRIGGERED, `lastRelevantRunId` present (DP-B2, `WhyNotView.tsx:91` — today's inference) | It has run — most recently at 9:42 pm. | It has run. The most recent run is on record. | `whyNot.headline.neverTriggered.ranFine` · `.noTime` | It did run · ● · info (not ok — the run page proves it) |
| N4 | ACTED_BUT_UNCONFIRMED | It ran at 9:42 pm, but the device never confirmed it acted. | It ran, but the device never confirmed it acted. | `whyNot.headline.actedButUnconfirmed` | It ran, but the device never confirmed · clock · warn |
| N5 | DISABLED | It's turned off, so it can't run. | — | `whyNot.headline.disabled` | It is turned off · ⏸ · unknown |
| N6 | `noCommandsIssued` true (any verdict) | It ran at 9:42 pm, but sent nothing — every step was skipped. | It ran, but sent nothing — every step was skipped. | `whyNot.headline.sentNothing` | Ran, but sent nothing · skip · warn |
| N7 | an unknown verdict string | Recorded as "PAUSED" — a verdict this dashboard can't explain yet. | — | `whyNot.headline.unknown` | Recorded as "…" · dotted · unknown |

N3 changes tone from today's `ok` to `info`: "It did run" is an inference from a non-null run id, and a run that lawfully skipped every target also carries one; the ok register is reserved for a confirmed outcome. When `FIRED_CONFIRMED` lands (EXPLAIN-6, §6) N3 splits into a confirmed row in ok and the inference row disappears.

## §4 The four empty states

Each is a fact stated calmly, never an error posture, never a blank, never a spinner. Start copy is HERO-0 §2; the changes and why are noted.

**Not fired** — the why-not card for NEVER_TRIGGERED with no run. Title `whyNot.neverTriggered.title` "It hasn't run yet." Body `whyNot.neverTriggered.body` "Nothing has set it off since this automation was loaded. It runs on {triggerSummary}. Nothing is wrong — it's waiting." (HERO-0 said "Not since this automation was loaded"; the full sentence reads aloud without the title.) The card keeps "What would make it run" with `triggerSummary` and, when the wire names one, "Watching: {entity}". No link is offered: there is no run to see. The scope "since it was loaded" is the honest scope until EXPLAIN-5 lands.

**No detail recorded** — the run page for a skeleton chain: `conditions[]` and `actions[]` empty, `actionCount` 0, and the run is from before the current automations were loaded (`automationName` null or `trigger.type` null — the prior-instance class HERO-0 §1 documents). The title `explain.chain.noDetail.title` "This run is on record, but its steps aren't." is the L1 headline for these runs (no device is named, because none is on record). The body `explain.chain.noDetail.body` "It happened before the current automations were loaded, so the run was kept but not its steps. Records are never removed." It is permanent for these runs (the era boundary) and reads as a fact. The chain renders the trigger step with its time, the body sentence as one step in place of the steps, and the terminal step ("Done, recorded no steps"). An empty chain whose name IS on record (a current automation that plans nothing) renders the headline `explain.headline.completed.none` instead — two facts, two sentences. HERO-0 wrote "the hub kept the run"; this is Register C, so the sentence is passive and names no actor.

**Reading not recorded** — an inline arm of the trigger step, not a card: `explain.trigger.readingNotRecorded` "{Trigger} set it off at {time} — the reading wasn't recorded." It replaces the trigger line whenever `firingValue` is null (today: every run). The L2 detail beneath says "value not recorded" in words. When the trigger subject is also null, the step line is the `because` null arm, and the reading marker is dropped (one honest sentence, not two).

**Confirmation unknown** — an action step in two states. Settled UNCONFIRMED: label `explain.action.unconfirmed.title` "Sent — the device never confirmed." and help `explain.action.unconfirmed.body` "The command was sent; no confirmation came back{reasonClause}. It may have worked — the record can't say." (HERO-0's "The hub sent {command} to {device}" named the actor and the raw command; the action line already names both, so the help states the fact.) Unsettled DISPATCHED: label `explain.action.pending.title` "Sent — waiting for the device to confirm." and help `explain.action.pending.body`; a colour-class command adds `explain.action.pending.color`. It appears on the run page (the step) and, as the why-not verdict ACTED_BUT_UNCONFIRMED, on the why-not card with the link into that run.

## §5 The confirmation states

The five modes the standing law keeps distinct, plus the three neighbours a chain also carries. Label and shape carry the distinction; colour reinforces; every row is AA in both themes (§8). Glyphs are the existing `MODE_GLYPHS` paths (`verdicts.ts:298`), so the build adds no new shape.

| Mode | Wire | Label (pill) | Glyph | Tone | Step line (key) | Help |
|---|---|---|---|---|---|---|
| confirmed | CONFIRMED (any `resultOutcome`, incl. null — the happy path) | Confirmed | check | ok | `explain.mode.confirmed.line` | The device's own report confirms it. |
| 4 held-DISPATCHED (provisional) | DISPATCHED, `settled` false | Sent — not settled yet | arrow, dashed outline | info | `explain.mode.heldDispatched.line` | The outcome can still change when the device reports. |
| 1 dispatched-and-timed-out | UNCONFIRMED / `timed_out` or `command_confirmation_timed_out` | Sent — no reply | clock | warn | `explain.mode.timedOut.line` | The device did not report within the time allowed for this kind of command. It may still have acted. |
| 2 superseded-same-attribute | `resultOutcome` `superseded` (DISPATCHED or FAILED) | Replaced | swap arrows | neutral | `explain.mode.superseded.line` | A later command changed the same setting, so this one stopped waiting. This is not a failure. |
| 3 acked-then-silent-forever | `resultOutcome` `acknowledged` on a settled UNCONFIRMED | Accepted, never confirmed | ack + dots | warn | `explain.mode.ackedSilent.line` | Accepting a command is not the same as doing it. No report followed. |
| 5 settled-FAILED-on-window-close | FAILED with a known-failed `resultOutcome` or none | Failed (sub-label: Rejected · Invalid · Not supported · Error · Bridge offline) | x | error | `explain.mode.settledFailed.line` | The recorded reason says why. |
| expired at restart | `resultOutcome` `expired_on_restart` | Expired at restart | restart arc | unknown | `explain.mode.expiredRestart.line` | Waiting does not survive a restart, so the outcome was closed unknown. This is bookkeeping, not a device fault. |
| skipped | SKIPPED, `command` null | Skipped | skip-to-end | unknown | `explain.mode.skipped.line` | — |
| not recorded / unknown string | outcome null or a string this build does not know | Not recorded / Recorded as "…" | dotted line | unknown | `explain.mode.notRecorded.line` | shown as recorded |

The pending window is per capability and owned by the hub: the UI runs no timer and shows no countdown. While an action is held-DISPATCHED the pill is dashed and the step help is calm; a colour-class command adds the slow-confirm hint, because colour temperature legitimately takes seconds where on/off takes well under one (Doc 08 §3.6, measured). There is no failure register inside a capability's window: the step never turns amber or red by elapsed time, only when the hub's next read carries a settled outcome. An idempotent command that produces no report (no change ⇒ no report) arrives from the hub as CONFIRMED (confirmed from cache or readback) and renders as Confirmed — never a spinner waiting for a report that will not come; the wire does not yet say "already in that state", so the copy does not either (§6, no key today). An `UNCONFIRMABLE` capability (the effect/identify class) renders honest UNCONFIRMED at once with `explain.mode.ackedSilent.unconfirmable` as its help — an acknowledgement is not confirmation, and no surface says the command was delivered. A superseded expectation expires: the older action renders Replaced, neutral, with no pending pill stranded. Live transitions (a held action settling on a later poll) are announced once through a polite `role="status"` region (`explain.a11y.live`), and the terminal step's line changes from "Done — one outcome has not settled yet" to "Done in {secs}s."

## §6 The EXPLAIN placeholders

Each surface says today's honest sentence — never a mock value — and the sentence it will say once Core carries the key. EXPLAIN v1.1.4 is being authored beside this lane; the keys are named as HERO-0 §3 names them, and the build swaps the string only when the key is present on the wire (the tri-state idiom: absent ≠ null ≠ value).

| Row | Surface | Today | Once the key lands | Key it waits for |
|---|---|---|---|---|
| EXPLAIN-1 | trigger step line | Hallway Motion set it off at 9:42 pm — the reading wasn't recorded. | Hallway Motion detected motion at 9:42 pm. (the verb from the value; the value itself in L2) | `trigger.firingValue` (FIRING-VALUE) |
| EXPLAIN-2 | headline `{because}` and the trigger step | …because something set it off at 9:42 pm (what isn't recorded). | …because Hallway Motion detected motion at 9:42 pm. | `trigger.subjectRef` on every run (the correlation gap) |
| EXPLAIN-3 | cascade line under the chain | Started by another run — which one isn't recorded. | ← See what triggered this run (a link) | `cascade.parentRunId` |
| EXPLAIN-4 | condition step line | The rule "state_equals" was false. (the type — L2 shows it is the type) | The rule "someone is home" was false. | condition text on `condition_evaluated` |
| EXPLAIN-5 | why-not body, NEVER_TRIGGERED | Nothing has set it off since this automation was loaded. | Nothing has set it off since {date}. / It has never run. | a stable definition key across reloads |
| EXPLAIN-6 | why-not headline N3 | It has run — most recently at 9:42 pm. (info, an inference) | It ran and the device confirmed, most recently at 9:42 pm. (ok) | `FIRED_CONFIRMED` verdict |
| EXPLAIN-7 | why-not "What would make it run" | {triggerSummary} alone (no "Watching:" line) | Watching: {entity} — for every trigger kind | per-permit `triggerRef` |
| EXPLAIN-8 | why-not body, DISABLED | Turn it on in your automation settings to let it run. When it was turned off isn't recorded. | Turned off {ago} by {origin}. Turn it on in your automation settings to let it run. | `disabledAt` + origin |
| EXPLAIN-9 | action step L2 "Confirmed" detail | Confirmed by the device's own report. (no time) | Confirmed at 9:42:03 pm, 0.4 s after the command. | `confirmedAt` / `settledAt` per action |

The list row's `lastReported` (EXPLAIN-10, resolved core-side by `94ae99d`) is outside the hero; where the hero shows a device's last report it says "No report time on record." for null and does not claim currency.

## §7 Copy table

Every string the hero shows, in Register C (no self-reference, no "we", never blames, never celebrates; the DAS §2.2 banned list was checked by script against every row — zero hits). "Level" is the Flesch–Kincaid grade of the string at the sample slots (target ≤ about 9; "frag." marks labels too short to grade); "Words/sentence" is the longest sentence in the string. Slot types: `Target`/`target` = display name | unnamed arm | dangling arm; `verb`/`verbPast` = command verb; `because` = trigger clause | its null arm; `time` = date-qualified clock; `Automation` = name | "An earlier automation"; `reasonClause` = " — {reason}" | "" ; `secs` = seconds to one decimal; `n`/`N` = step index/count.

| Key | String (slots in braces) | Where | Slots | Level | Words/sentence |
|---|---|---|---|---|---|
| `explain.headline.completed.confirmed` | {Target} {verbPast} because {because}. | L1 headline | Target·verbPast·because | 6.9 | 12 |
| `explain.headline.completed.dispatched` | {Target} was asked to {verb} because {because} — no confirmation yet. | L1 headline | Target·verb·because | 9.1 | 18 |
| `explain.headline.completed.unconfirmed` | {Target} was asked to {verb} because {because} — it never confirmed. | L1 headline | Target·verb·because | 8.4 | 18 |
| `explain.headline.completed.failed` | {Target} was asked to {verb} because {because}, but the command failed. | L1 headline | Target·verb·because | 7.8 | 19 |
| `explain.headline.completed.skipped` | Nothing was sent to {target} when {because} — that step was skipped. | L1 headline | target·because | 7.0 | 18 |
| `explain.headline.completed.none` | {Automation} ran when {because} and recorded no steps. | L1 headline (actions[] empty, actionCount 0, current-instance name) | Automation·because | 7.6 | 15 |
| `explain.headline.completed.silentSkip` | {Automation} ran when {because}, but nothing was changed. | L1 headline (actions[] empty, actionCount>0, commandCount 0) | Automation·because | 7.6 | 15 |
| `explain.headline.skipped.frame` | {Automation} skipped this run when {because}. | L1 headline, sentence 1 | Automation·because | 6.8 | 13 |
| `explain.headline.failed.frame` | {Automation} failed part-way when {because}. | L1 headline, sentence 1 | Automation·because | 6.8 | 13 |
| `explain.headline.cancelled.frame` | {Automation} was cancelled after {because}. | L1 headline, sentence 1 | Automation·because | 9.1 | 12 |
| `explain.headline.interrupted.frame` | {Automation} was cut off before it finished, after {because}. | L1 headline, sentence 1 | Automation·because | 9.1 | 16 |
| `explain.headline.tail.confirmed` | {Target} did {verb} first, and confirmed it. | L1 headline, sentence 2 | Target·verb | 2.3 | 9 |
| `explain.headline.tail.dispatched` | {Target} was asked to {verb}; no confirmation has come back. | L1 headline, sentence 2 | Target·verb | 2.5 | 7 |
| `explain.headline.tail.unconfirmed` | {Target} was asked to {verb}; it never confirmed. | L1 headline, sentence 2 | Target·verb | 1.7 | 7 |
| `explain.headline.tail.failed` | The command to {target} failed. | L1 headline, sentence 2 | target | 2.5 | 6 |
| `explain.headline.tail.skipped` | Nothing was sent to {target}. | L1 headline, sentence 2 | target | 2.5 | 6 |
| `explain.headline.tail.none` | (empty) | L1 headline, sentence 2 (no leading action: the frame stands alone) | — | frag. | 0 |
| `explain.headline.tail.superseded` | {Target} was asked to {verb}, then a newer command replaced it. | L1 tail override when mode = superseded (DISPATCHED or FAILED cell) | Target·verb | 4.9 | 13 |
| `explain.headline.tail.expiredRestart` | {Target} was asked to {verb}; the hub restarted before it could confirm. | L1 tail override when mode = expired-restart (FAILED cell) | Target·verb | 2.3 | 7 |
| `explain.headline.completed.superseded` | {Target} was asked to {verb} because {because}, then a newer command replaced it. | L1 override, COMPLETED × (DISPATCHED|FAILED) when mode = superseded | Target·verb·because | 9.3 | 21 |
| `explain.headline.completed.expiredRestart` | {Target} was asked to {verb} because {because}; the hub restarted before it could confirm. | L1 override, COMPLETED × FAILED when mode = expired-restart | Target·verb·because | 5.4 | 15 |
| `explain.slot.because` | {Trigger} {triggerVerb} at {time} | the {because} slot | Trigger·triggerVerb·time | 6.4 | 7 |
| `explain.slot.because.unrecorded` | something set it off at {time} (what isn't recorded) | the {because} slot, trigger.subjectRef null | time | 3.7 | 10 |
| `explain.slot.because.dangling` | entity {id} (not in this hub's registry) changed at {time} | the {because} slot, dangling ref on a complete census | id·time | 4.8 | 11 |
| `explain.slot.target.unnamed` | a device the run didn't name | the {target} slot, targetRef null (sentence-initial: capitalised) | — | 0.5 | 6 |
| `explain.slot.target.dangling` | entity {id} (not in this hub's registry) | the {target} slot, dangling ref | id | 5.0 | 7 |
| `explain.slot.automation.earlier` | An earlier automation | the {Automation} slot, automationName null | — | frag. | 3 |
| `explain.slot.verb.unknown` | run "{command}" | the {verb} slot, a command with no plain verb | command | frag. | 3 |
| `explain.slot.verbPast.unknown` | ran "{command}" | the {verbPast} slot, a command with no plain verb | command | frag. | 3 |
| `explain.slot.verb.null` | act | the {verb} slot, command null (unreachable in a dispatched cell; defined so no cell infers) | — | frag. | 1 |
| `explain.slot.verbPast.null` | acted | the {verbPast} slot, command null | — | frag. | 1 |
| `explain.slot.time.unparseable` | an unrecorded time | the {time} slot when parseInstant fails | — | frag. | 3 |
| `explain.slot.triggerVerb.null` | changed | the {triggerVerb} slot, firingValue null (today: always) | — | frag. | 1 |
| `whyNot.neverTriggered.title` | It hasn't run yet. | why-not card title | — | frag. | 4 |
| `whyNot.neverTriggered.body` | Nothing has set it off since this automation was loaded. It runs on {triggerSummary}. Nothing is wrong — it's waiting. | why-not card body | triggerSummary | 3.4 | 10 |
| `explain.chain.noDetail.title` | This run is on record, but its steps aren't. | run page, skeleton chain (era boundary) | — | 2.3 | 9 |
| `explain.chain.noDetail.body` | It happened before the current automations were loaded, so the run was kept but not its steps. Records are never removed. | run page, skeleton chain body | — | 5.4 | 17 |
| `explain.trigger.readingNotRecorded` | {Trigger} set it off at {time} — the reading wasn't recorded. | trigger step line, firingValue null | Trigger·time | 4.8 | 12 |
| `explain.action.unconfirmed.title` | Sent — the device never confirmed. | action step, settled UNCONFIRMED | — | frag. | 5 |
| `explain.action.unconfirmed.body` | The command was sent; no confirmation came back{reasonClause}. It may have worked — the record can't say. | action step help, settled UNCONFIRMED | reasonClause = ' — {reason}' | '' | 2.0 | 8 |
| `explain.action.pending.title` | Sent — waiting for the device to confirm. | action step, unsettled DISPATCHED | — | 4.0 | 7 |
| `explain.action.pending.body` | Most devices confirm within a second or two. This updates when the device reports. | action step help, unsettled DISPATCHED | — | 4.8 | 8 |
| `explain.action.pending.color` | Colour changes confirm slowly on some bulbs — this can take several seconds. | action step help, unsettled DISPATCHED, colour-class command | — | 6.8 | 12 |
| `explain.mode.confirmed.label` | Confirmed | action pill | — | frag. | 1 |
| `explain.mode.confirmed.line` | {Target} {verbPast}. | action step line | Target·verbPast | frag. | 4 |
| `explain.mode.confirmed.help` | The device's own report confirms it. | action step help / pill title | — | 4.4 | 6 |
| `explain.mode.heldDispatched.label` | Sent — not settled yet | action pill (dashed, provisional) | — | frag. | 4 |
| `explain.mode.heldDispatched.line` | {Target} was asked to {verb}; waiting for it to confirm. | action step line | Target·verb | 1.5 | 7 |
| `explain.mode.heldDispatched.help` | The outcome can still change when the device reports. | action step help | — | 3.7 | 9 |
| `explain.mode.timedOut.label` | Sent — no reply | action pill | — | frag. | 3 |
| `explain.mode.timedOut.line` | {Target} was asked to {verb}; no reply came within its window. | action step line | Target·verb | 2.4 | 7 |
| `explain.mode.timedOut.help` | The device did not report within the time allowed for this kind of command. It may still have acted. | action step help | — | 3.0 | 14 |
| `explain.mode.superseded.label` | Replaced | action pill | — | frag. | 1 |
| `explain.mode.superseded.line` | {Target} was asked to {verb}, then a newer command replaced it. | action step line | Target·verb | 4.9 | 13 |
| `explain.mode.superseded.help` | A later command changed the same setting, so this one stopped waiting. This is not a failure. | action step help | — | 3.0 | 12 |
| `explain.mode.ackedSilent.label` | Accepted, never confirmed | action pill | — | frag. | 3 |
| `explain.mode.ackedSilent.line` | {Target} accepted the command to {verb}, but never reported acting. | action step line | Target·verb | 6.8 | 12 |
| `explain.mode.ackedSilent.help` | Accepting a command is not the same as doing it. No report followed. | action step help | — | 3.3 | 10 |
| `explain.mode.ackedSilent.unconfirmable` | This kind of command is acknowledged but never reported back, so it cannot be confirmed. | action step help, effect/identify-class command | — | 7.6 | 15 |
| `explain.mode.settledFailed.label` | Failed | action pill (sub-labels: Rejected · Invalid · Not supported · Error · Bridge offline · No reply) | — | frag. | 1 |
| `explain.mode.settledFailed.line` | The command to {verb} {target} failed{reasonClause}. | action step line | verb·target·reasonClause | 2.3 | 8 |
| `explain.mode.settledFailed.help` | The recorded reason says why. | action step help | — | frag. | 5 |
| `explain.mode.expiredRestart.label` | Expired at restart | action pill | — | frag. | 3 |
| `explain.mode.expiredRestart.line` | {Target} was asked to {verb}; the hub restarted before it could confirm. | action step line | Target·verb | 2.3 | 7 |
| `explain.mode.expiredRestart.help` | Waiting does not survive a restart, so the outcome was closed unknown. This is bookkeeping, not a device fault. | action step help | — | 4.9 | 12 |
| `explain.mode.skipped.label` | Skipped | action pill | — | frag. | 1 |
| `explain.mode.skipped.line` | Skipped before any command was sent. | action step line, command null | — | 2.5 | 6 |
| `explain.mode.skipped.lineNamed` | Nothing was sent to {target} — this step was skipped. | action step line, command null, targetRef present | target | 2.5 | 10 |
| `explain.mode.notRecorded.label` | Not recorded | action pill | — | frag. | 2 |
| `explain.mode.notRecorded.line` | What happened to {target} isn't recorded. | action step line, outcome null/unknown string | target | 4.0 | 7 |
| `explain.mode.unknownOutcome.label` | Recorded as "{outcome}" | action pill, an outcome string this build does not know | outcome | frag. | 3 |
| `explain.mode.unknownOutcome.help` | The device reported an outcome this dashboard does not recognise yet — shown as recorded. | action step help | — | 7.6 | 14 |
| `explain.terminal.completed` | Done in {secs}s. | terminal step line | secs | frag. | 4 |
| `explain.terminal.completed.open` | Done in {secs}s — {count} outcomes have not settled yet. | terminal step line (one: 'one outcome has not settled yet') | secs·count | 0.8 | 10 |
| `explain.terminal.completed.nothing` | Finished in {secs}s, but nothing was changed. | terminal step line, silent skip | secs | 2.3 | 8 |
| `explain.terminal.skipped` | Skipped{reasonClause}. | terminal step line | reasonClause | frag. | 1 |
| `explain.terminal.failed` | Failed{reasonClause}. | terminal step line | reasonClause | frag. | 1 |
| `explain.terminal.cancelled` | Cancelled. | terminal step line | — | frag. | 1 |
| `explain.terminal.interrupted` | Cut off before it finished. | terminal step line | — | frag. | 5 |
| `whyNot.headline.conditionNotMet` | It was set off at {time}, but a condition was false, so it didn't act. | why-not L1 | time (null → drop 'at {time}') | 3.6 | 16 |
| `whyNot.headline.conditionNotMet.noTime` | It was set off, but a condition was false, so it didn't act. | why-not L1, lastEvaluation null | — | 3.1 | 13 |
| `whyNot.headline.neverTriggered` | It hasn't run yet. | why-not L1, lastRelevantRunId null | — | frag. | 4 |
| `whyNot.headline.neverTriggered.ranFine` | It has run — most recently at {time}. | why-not L1, NEVER_TRIGGERED with a run id (today's inference) | time | 2.3 | 8 |
| `whyNot.headline.neverTriggered.ranFine.noTime` | It has run. The most recent run is on record. | why-not L1, same, lastEvaluation null | — | 0.5 | 7 |
| `whyNot.headline.actedButUnconfirmed` | It ran at {time}, but the device never confirmed it acted. | why-not L1 | time | 3.7 | 12 |
| `whyNot.headline.disabled` | It's turned off, so it can't run. | why-not L1 | — | -1.1 | 7 |
| `whyNot.headline.sentNothing` | It ran at {time}, but sent nothing — every step was skipped. | why-not L1, noCommandsIssued true | time | 3.7 | 12 |
| `whyNot.headline.unknown` | Recorded as "{verdict}" — a verdict this dashboard can't explain yet. | why-not L1, unknown verdict string | verdict | 4.8 | 10 |
| `whyNot.body.conditionNotMet` | It ran on {triggerSummary}, checked its conditions, and one was false. The run shows which. | why-not body | triggerSummary | 2.3 | 14 |
| `whyNot.body.actedButUnconfirmed` | The command was sent. No confirmation came back, so whether it worked isn't known. | why-not body | — | 3.2 | 10 |
| `whyNot.body.disabled` | Turn it on in your automation settings to let it run. When it was turned off isn't recorded. | why-not body | — | 3.0 | 11 |
| `whyNot.body.sentNothing` | Every step ended without sending a command. Why each was skipped isn't recorded yet. | why-not body | — | 4.0 | 7 |
| `whyNot.body.ranFine` | Whether anything changed is on the run's own page. | why-not body | — | 3.7 | 9 |
| `whyNot.link.conditionNotMet` | See which condition blocked it → | why-not link | — | frag. | 5 |
| `whyNot.link.ranFine` | See that run — including whether anything changed → | why-not link | — | 7.4 | 7 |
| `whyNot.link.actedButUnconfirmed` | See the run where the device never confirmed → | why-not link | — | 3.8 | 8 |
| `whyNot.link.sentNothing` | See the run that sent no commands → | why-not link | — | 0.6 | 7 |
| `whyNot.kv.trigger` | What would make it run | why-not key | — | frag. | 5 |
| `whyNot.kv.watching` | Watching: {entity} | why-not value line, triggerRef present | entity | frag. | 3 |
| `whyNot.kv.lastChecked` | Last checked | why-not key | — | frag. | 2 |
| `whyNot.kv.lastChecked.unclean` | It ran, but didn't finish cleanly. | why-not value, conditionsResult null | — | 2.5 | 6 |
| `whyNot.kv.neverChecked` | Never checked yet. | why-not value, lastEvaluation null | — | frag. | 3 |
| `explain.hub.title` | Ask your home why | hub page title | — | frag. | 4 |
| `explain.hub.lede` | See what your home did — and what it didn't. | hub lede | — | -0.3 | 9 |
| `explain.hub.fire.kicker` | Why did | hub card | — | frag. | 2 |
| `explain.hub.fire.title` | something happen? | hub card | — | frag. | 2 |
| `explain.hub.fire.text` | See any run step by step — what set it off, what it checked, and whether the device confirmed. | hub card | — | 5.2 | 18 |
| `explain.hub.fire.go` | See recent runs → | hub card | — | frag. | 3 |
| `explain.hub.not.kicker` | Why didn't | hub card | — | frag. | 2 |
| `explain.hub.not.title` | something happen? | hub card | — | frag. | 2 |
| `explain.hub.not.text` | Expected a light to come on and it didn't? Find out whether a condition was false, nothing set it off, or the device never confirmed. | hub card | — | 4.9 | 16 |
| `explain.hub.not.go` | Diagnose an automation → | hub card | — | frag. | 3 |
| `explain.hub.autos.noRuns` | No runs yet | hub automation row | — | frag. | 3 |
| `explain.hub.autos.noRunsSinceLoad` | Hasn't run since it was loaded | hub automation row (lastRunId null, honest scope) | — | -1.4 | 6 |
| `explain.permanence` | Rebuilt from the permanent activity log. Nothing here is ever deleted, so this run is always here. | run page footer | — | 6.5 | 11 |
| `explain.nullName.note` | This run happened under an earlier version of your automations, so its name is no longer on record. The run itself is preserved. | run page, automationName null | — | 6.3 | 18 |
| `explain.cascade.parentUnrecorded` | Started by another run — which one isn't recorded. | run page, depth>0, parentRunId null | — | 3.8 | 8 |
| `explain.cascade.parent` | ← See what triggered this run | run page, parentRunId present | — | frag. | 5 |
| `explain.trigger.detail` | Trigger | L2 detail label | — | frag. | 1 |
| `explain.trigger.detail.noType` | recorded before the current automations | L2 detail, trigger.type null | — | frag. | 5 |
| `explain.trigger.detail.noValue` | value not recorded | L2 detail, firingValue null | — | frag. | 3 |
| `explain.condition.line` | The rule "{condition}" {verdict}. | condition step line (verdict: was true · was false · was not checked) | condition·verdict | 2.5 | 6 |
| `explain.condition.atTheTime` | At the time | L2 detail label | — | frag. | 3 |
| `explain.condition.noReading` | {entity} {attr} had no reading yet. | L2 detail, observedState value null | entity·attr | 4.0 | 7 |
| `explain.action.detail.command` | Command | L2 detail label | — | frag. | 1 |
| `explain.action.detail.reason` | Recorded reason | L2 detail label | — | frag. | 2 |
| `explain.action.detail.outcome` | Recorded outcome | L2 detail label | — | frag. | 2 |
| `explain.detail.notRecorded` | not recorded | L2 detail value for any null | — | frag. | 2 |
| `explain.a11y.step` | Step {n} of {N}: {kind} — {label}. | screen-reader text on each marker | n·N·kind·label | 3.7 | 6 |
| `explain.a11y.chain` | Step-by-step explanation, from trigger to outcome | aria-label on the <ol> | — | 6.7 | 8 |
| `explain.a11y.provisional` | Provisional — may still change | screen-reader suffix on a dashed pill | — | frag. | 4 |
| `explain.a11y.live` | Updated: {label} | role=status announcement on a settle | label | frag. | 2 |
| `explain.error.title` | This explanation couldn't be loaded. | error card title | — | frag. | 5 |
| `explain.error.body` | The hub answered with an error. The record itself is safe — try again. | error card body | — | 3.3 | 7 |
| `explain.error.retry` | Try again | error card button | — | frag. | 2 |
| `explain.offline.title` | The hub can't be reached right now. | offline card title | — | -1.1 | 7 |
| `explain.offline.body` | Nothing is lost. This page fills in when the connection returns. | offline card body | — | 2.6 | 8 |
| `explain.replaying.title` | The hub is catching up after a restart. | replaying card title | — | 3.8 | 8 |
| `explain.replaying.body` | This takes a moment. Explanations appear as the log is replayed. | replaying card body | — | 4.8 | 7 |
| `explain.loading` | Loading this run… | loading state (never an eternal spinner: becomes the error card on failure) | — | frag. | 3 |
| `explain.terminal.noSteps` | Done, recorded no steps. | terminal step line, a completed run with `actionCount` 0 (SPEC §4's sentence, given its key) | — | 0.7 | 4 |
| `whyNot.headline.actedButUnconfirmed.noTime` | It ran, but the device never confirmed it acted. | why-not L1, N4 with `lastEvaluation` null (§3 N4's no-time arm, given its key) | — | 3.7 | 9 |
| `whyNot.headline.sentNothing.noTime` | It ran, but sent nothing — every step was skipped. | why-not L1, N6 with `lastEvaluation` null (§3 N6's no-time arm, given its key) | — | 3.7 | 9 |
| `explain.headline.completed.notRecorded` | {Target} was asked to {verb} because {because}; what happened isn't recorded. | L1 headline, the headline action's outcome null or a string this build does not know (D5; today rendered through `explain.mode.notRecorded.line`) | Target·verb·because | 6.8 | 15 |
| `explain.mode.notRecorded.help` | What happened to this step was not recorded. The step itself is preserved. | action step help / pill title, outcome null (HEAD's sentence, given its key) | — | 4.1 | 7 |
| `explain.mode.settledFailed.lineNoCommand` | No command was sent to {target} — this step failed{reasonClause}. | action step line, FAILED with `command` null and a named target (D2: the `act` arm is the SPEC's null verb and reads wrongly) | target·reasonClause | 4.9 | 13 |

## §8 Accessibility

Contrast was computed from the generated `tokens.css` values at `eabdbb1` (WCAG 2.x relative-luminance formula; the pill text is 11–12 px, so the 4.5:1 small-text bar applies everywhere). Every state pair the hero uses passes AA in both themes: dark — ok 7.96 on ok-bg / 9.07 on surface, warn 7.92 / 8.97, error 6.97 / 7.19, info 7.47 / 8.04, unknown 7.24 / 7.59, body text 15.14, secondary 6.98, muted 4.92, link 8.04; light — ok 4.76 / 5.40, warn 5.84 / 6.56, error 4.64 / 5.40, info 6.04 / 6.79, unknown 5.17 / 6.04, body 17.46, secondary 6.79, muted 4.55, link 4.76. Three pairs the hero must avoid because they fall under 4.5: muted text on the sunk surface (3.96 dark, 3.95 light), a link on the sunk surface in light (4.12), and muted text on the page background in light (4.28). The L2 detail body therefore uses secondary text (5.62 / 5.89 on sunk), and any link inside an L2 body uses body text with an underline rather than the link colour. The light ok and error pills sit at 4.76 and 4.64 — passing, with little margin; §9 asks for one token so a future darkening is a token change, not a component change.

Shape and label on every state: the marker glyph (check, arrow, clock, swap, ack-dots, x, arc, skip, dotted) and the label text carry the meaning; colour and the dashed provisional outline reinforce. Each marker is `aria-hidden` and the step carries `explain.a11y.step` as visually-hidden text ("Step 2 of 4: action — Confirmed."); a provisional pill appends `explain.a11y.provisional`. The chain is a semantic `<ol>` with `explain.a11y.chain` as its label, never a tree widget. Focus order follows reading order: headline → each step's `<summary>` → the cascade link → the permanence footer; the `<summary>` elements are the only stops inside the chain, each with the visible blue focus ring (`--hs-focus-ring`), and a pill is never a focus stop (its help is the pill's `title` and is repeated as text where it matters — §5's help column — so nothing is hover-only). Under `prefers-reduced-motion: reduce` the settle transition has no animation: the pill swaps state without fade, the connector rail does not draw. Sentences stay ≤ 21 words (§7 measures each), which is itself the cognitive-accessibility half of the plain-language rule.

## §9 Tokens

Existing tokens, by state (the same names in both themes, so no component changes between them): the headline uses `--hs-text`, `--hs-text-md`, `--hs-weight-medium`, `--hs-leading-relaxed`; step lines `--hs-text-base`; help lines and the cascade line `--hs-text-secondary`, `--hs-text-sm`; L2 summaries `--hs-text-muted` on the surface; L2 bodies `--hs-text-secondary` on `--hs-surface-sunk` in `--hs-font-mono` at `--hs-text-xs`; markers and pills `--hs-{ok,warn,error,info,unknown}-500` on `--hs-{ok,warn,error,info,unknown}-bg`; the neutral tone (Replaced, Cancelled) `--hs-text-secondary` on `--hs-surface-sunk`; the connector rail `--hs-border`; the provisional outline `currentColor` dashed at `--hs-border-width`; the permanence footer `--hs-ok-bg` with a `--hs-ok-500` left rule; radii `--hs-radius-sm` / `--hs-radius-pill`; spacing `--hs-space-1..4`; the focus ring `--hs-focus-ring`. Mode 1 (Sent — no reply) and mode 3 (Accepted, never confirmed) share the warn hue by design — the clock and ack-dots glyphs and the labels tell them apart, which is what the law asks; a second amber would be decoration.

Requests (named, justified, not changed here): (T1) a light-theme `--hs-text-muted` that clears 4.5:1 on `--hs-bg` — today's `neutral-500` reads 4.28 on the page background, so any muted text that leaves a card is below AA in light; `neutral-600` (#515c6b) would read 6.38 and is already the light secondary. (T2) a `--hs-status-on-bg` foreground pair (or a darkened light `--hs-ok-500` / `--hs-error-500`) so the two light pills with the least margin (4.76, 4.64) are not one tint away from failing when a designer next adjusts the ramp. Neither request blocks the build; both are token-source (`tokens.dtcg.json`) edits under the drift-guard.

## §10 The acceptance script

Six sentences a stranger reads aloud from the mockups, two per question, and what "right" means for each. Why did it fire — (1) "Hallway Light turned on because Hallway Motion detected motion at 9:42 pm." Right means the person can say which device changed, what caused it, and when, and knows the device confirmed. (2) "Evening Lights skipped this run when Hallway Motion detected motion at 9:42 pm. Nothing was sent to Hallway Light." Right means the person does not believe the light turned on. Why didn't it — (3) "It hasn't run yet. Nothing has set it off since this automation was loaded." Right means the person understands nothing is broken and knows what would set it off. (4) "It was set off at 9:42 pm, but a condition was false, so it didn't act." Right means the person knows it did get triggered, and that a rule — not a device — stopped it. Did it actually confirm — (5) "Hallway Light was asked to turn on because Hallway Motion detected motion at 9:42 pm — it never confirmed." Right means the person knows the command was sent and that whether it worked is not known, and is not alarmed. (6) "Hallway Light was asked to turn on, then a newer command replaced it." Right means the person knows nothing failed. A seventh check runs on every mockup: the person points at any coloured marker and names its state from the label and shape with the colour covered.

## §11 Build rows for the implementing lane

In order, each small, each with its test first. (B1) `format.ts`: replace the single `causalSentence` with the §3 grammar — the COMPLETED clause per leading outcome, the frame + tail for the other statuses, the mode override via `actionVerdict()`, the slot null arms as named constants; `format.test.ts` pins one sentence per cell of the 30-row table plus the mode overrides and every slot null arm (the current test pins only the happy path). (B2) `i18n.ts`: move the hero strings behind `t()` under the §7 keys, including the permanence footer without the product name; a test asserts no hero string contains `BRAND.productName` or "we". (B3) `WhyNotView.tsx`: the N1–N7 L1 sentence with the no-time arm; N3 tone `info` and body `whyNot.body.ranFine`; the DISABLED body with the "isn't recorded" sentence. (B4) `CausalChain.tsx`: split "genuinely empty" into the era-boundary card (`explain.chain.noDetail.*`) and the current-instance `…completed.none` headline; the trigger step's reading-not-recorded arm as the line, not the L2 detail only. (B5) `CausalChain.tsx` + `StatusPill.tsx`: the visually-hidden `explain.a11y.step` text per step, `explain.a11y.provisional`, and one `role="status"` announcement on a settle; extend `a11y.test.tsx`. (B6) `CausalChain.module.css`: L2 body text to `--hs-text-secondary` where it is not already; a reduced-motion rule for the pill swap. (B7) `mockData.ts` / `scenarios.ts`: a `hero-states` scenario carrying one run per §3 row that today's emitter can produce (COMPLETED × each outcome, the silent skip, the era skeleton, one SKIPPED/FAILED/CANCELLED/INTERRUPTED run) with the wire's null arms — `firingValue` null everywhere, `resultOutcome` null beside CONFIRMED (the `'acknowledged'` false value nulled). (B8) the EXPLAIN-1..9 swaps, each one row, landed only as the matching key appears on the wire behind the tri-state validator. (B9) the §9 token requests, if ruled, in `tokens.dtcg.json` with `npm run tokens`. Every row ships behind `npm run verify` green or explicitly deferred to Nick's desk (FE-NULL-1 O3 stands: the Linux shell cannot run the Windows toolchain).

## §12 Open questions for Nick

Q1 — the N3 inference wording, until `FIRED_CONFIRMED` lands. Options: (a) "It has run — most recently at {time}." in the info register with the run link (this spec); (b) keep today's "It did run" in ok; (c) render N3 as N2 "It hasn't run yet" plus the link, refusing the inference entirely. Recommendation (a): honest about what the id proves and no more. Does not block the build.

Q2 — the sent-but-unconfirmed phrasing in the headline. Options: (a) "was asked to turn on" (this spec — plain, makes no delivery claim); (b) "was sent the command to turn on" (closer to the wire, longer); (c) "should be turning on" (reads as a prediction, rejected by the honesty law but listed for completeness). Recommendation (a). Does not block.

Q3 — the permanence footer without self-reference. Register C forbids the product name and "we" in UI copy, and today's `hero.permanence` uses the name. Options: (a) "Rebuilt from the permanent activity log. Nothing here is ever deleted, so this run is always here." (this spec, name-free, rename-proof); (b) keep the name via {{NAME}} as the one sanctioned brand moment on the run page. Recommendation (a). Does not block — the key is the same either way.

Q4 — the automation-led frame for non-completed runs breaks device-backward order on purpose. Options: (a) automation-led frame + device tail (this spec: a skipped run never opens with a device acting); (b) device-led everywhere with a negated verb ("Hallway Light did not turn on because Evening Lights skipped this run…"). Recommendation (a): (b) reads as an accusation of the device. Does not block.

Q5 — the two §9 token requests (T1, T2). Options: (a) rule both now for the next token touch; (b) rule T1 only (the AA miss); (c) defer both. Recommendation (a). Neither blocks; T1 is an AA correction wherever muted text meets the light page background.

Q6 — the "no detail recorded" tell. The spec keys the era-boundary card on empty arrays with `automationName` or `trigger.type` null (HERO-0's prior-instance class). Options: (a) that tell (this spec); (b) ask Core for an explicit `skeleton: true` (or an `era` field) on the chain read as a cross-lane ask — the only certain signal. Recommendation (a) now and (b) as a v1.1.4+ ask. Does not block.

Cross-lane asks recorded, not decided here: the §6 keys (EXPLAIN v1.1.4 owns them); the Q6(b) skeleton marker; a `confirmedFromCache`/readback indicator so the idempotent no-report case can say "already in that state" (no key today; the spec says Confirmed).
