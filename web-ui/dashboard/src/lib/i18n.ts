/*
 * Brand + i18n (FE-3 — name-light + D-FE-9 i18n-readiness).
 * ---------------------------------------------------------------------------
 * 1) BRAND.productName is the SINGLE source of truth for the product name. The rename (W-11)
 *    is unratified, so the value stays "HomeSynapse" — but every user-facing surface references
 *    this token, so the eventual swap is one line. NEVER hardcode the product name (name-light).
 *
 * 2) Copy is a KEYED catalog, not inline strings — the i18n-readiness seam. V1 ships English
 *    only. To add a locale: provide another Messages catalog and resolve `locale` from
 *    navigator.language / a stored preference; the same seam is where RTL direction and Intl
 *    number/date/relative-time formatting attach. Keys are stable; only values localize. (Most
 *    enum-label copy still lives in format.ts; migrating those maps behind t() is the same
 *    mechanical pattern and can follow.)
 */

export const BRAND = {
  /** The product name. Unratified rename (W-11) flips this one value; nothing else changes. */
  productName: 'HomeSynapse',
} as const;

/** V1 locale. Reserved seam: resolve from navigator/stored preference when locales are added. */
export const locale = 'en' as const;

const en = {
  'auth.tokenHelp': `Paste the pairing token from your ${BRAND.productName} device. You’ll find it in`,
  'boot.startingBody': `${BRAND.productName} is catching up to live — this takes a moment after a restart.`,
  'devices.lede': `Everything ${BRAND.productName} can see in your home.`,
  'health.live': `${BRAND.productName} is up to date and processing events in real time.`,
  'overview.live': `${BRAND.productName} is live and watching your home in real time.`,
  'overview.catchingUp': `${BRAND.productName} is catching up after a restart — this only takes a moment.`,
  'origin.external.phrase': `outside ${BRAND.productName}`,
  // The M7.5c gap, rendered honestly (FE-1): the hub doesn't serve this read yet.
  'events.notServedYet.title': 'Your hub doesn’t share the activity feed yet.',
  'events.notServedYet.hint': `Everything else on this dashboard is live. The feed arrives with a ${BRAND.productName} update — nothing is wrong.`,
  // FE-113 (v1.1.3): the device list's freshness cell — TWO facts, TWO sentences
  // (FE-HONEST-1 §10-H/I). The key ABSENT = a hub that does not serve `lastReported`
  // on the list (pre-v1.1.3); the key PRESENT-BUT-NULL = this hub has nothing on record.
  'devices.freshness.noClaimTitle':
    'Whether this reading is current is not shown in this list — open the device to see when it last reported.',
  'devices.freshness.nullTitle': 'This hub has no report time on record for this entity.',
  // The muted secondary line under an entity when the wire carried its device's id.
  'devices.deviceIdLabel': 'Device',
  // HERO-1c correction D3 (2026-09-13): the app's generic state-card pair — every non-hero page's
  // loading / error card (feedback.tsx defaults). App copy, not SPEC §7 (§7 is the hero's table);
  // the hero views pass their own `explain.*` rows through Resource's `labels`.
  'ui.loading': 'Loading…',
  'ui.error.title': "This page couldn't be loaded.",
  'ui.error.body': 'The hub answered with an error. Try again.',
  'ui.error.retry': 'Try again',
  // HERO-1d D3 (2026-09-13): the enabled / disabled pill labels of the hub's automation rows — app copy
  // in the `ui.*` register like the pair above (AutomationsView still carries its own literals).
  'ui.on': 'On',
  'ui.off': 'Off',
  /* ---- HERO-1b B2 (2026-09-12): the explainability hero copy — SPEC §7, all 140 keyed rows,
   * verbatim (design/hero-v1/SPEC.md:136–:275). Register C: no product name, no "we", never
   * blames, never celebrates. The permanence footer is the Q3 (a) sentence (name-free,
   * rename-proof); the one edit from the table is the registry phrase, which keeps the
   * test-locked typographic apostrophe of format.ts UNRESOLVED_REF_PHRASE. Slots are
   * {braces}; callers fill them (format.ts fill()). Test-locked by i18n.test.ts. */
  // explain.headline.*
  "explain.headline.completed.confirmed": "{Target} {verbPast} because {because}.",
  "explain.headline.completed.dispatched": "{Target} was asked to {verb} because {because} — no confirmation yet.",
  "explain.headline.completed.unconfirmed": "{Target} was asked to {verb} because {because} — it never confirmed.",
  "explain.headline.completed.failed": "{Target} was asked to {verb} because {because}, but the command failed.",
  "explain.headline.completed.skipped": "Nothing was sent to {target} when {because} — that step was skipped.",
  "explain.headline.completed.none": "{Automation} ran when {because} and recorded no steps.",
  "explain.headline.completed.silentSkip": "{Automation} ran when {because}, but nothing was changed.",
  "explain.headline.skipped.frame": "{Automation} skipped this run when {because}.",
  "explain.headline.failed.frame": "{Automation} failed part-way when {because}.",
  "explain.headline.cancelled.frame": "{Automation} was cancelled after {because}.",
  "explain.headline.interrupted.frame": "{Automation} was cut off before it finished, after {because}.",
  "explain.headline.tail.confirmed": "{Target} did {verb} first, and confirmed it.",
  "explain.headline.tail.dispatched": "{Target} was asked to {verb}; no confirmation has come back.",
  "explain.headline.tail.unconfirmed": "{Target} was asked to {verb}; it never confirmed.",
  "explain.headline.tail.failed": "The command to {target} failed.",
  "explain.headline.tail.skipped": "Nothing was sent to {target}.",
  "explain.headline.tail.none": "",
  "explain.headline.tail.superseded": "{Target} was asked to {verb}, then a newer command replaced it.",
  "explain.headline.tail.expiredRestart": "{Target} was asked to {verb}; the hub restarted before it could confirm.",
  "explain.headline.completed.superseded": "{Target} was asked to {verb} because {because}, then a newer command replaced it.",
  "explain.headline.completed.expiredRestart": "{Target} was asked to {verb} because {because}; the hub restarted before it could confirm.",
  // HERO-1c C0 (2026-09-13): the hub's §7 amendment rows (SPEC.md:276–:279, the HERO-1b audit's
  // D2/D3/D5). This one is D5's headline cell for a null/unknown leading outcome — ADDED, not yet
  // consumed (the headline audit gates it; today the headline renders explain.mode.notRecorded.line).
  "explain.headline.completed.notRecorded": "{Target} was asked to {verb} because {because}; what happened isn't recorded.",
  // explain.slot.*
  "explain.slot.because": "{Trigger} {triggerVerb} at {time}",
  "explain.slot.because.unrecorded": "something set it off at {time} (what isn't recorded)",
  "explain.slot.because.dangling": "entity {id} (not in this hub’s registry) changed at {time}",
  "explain.slot.target.unnamed": "a device the run didn't name",
  "explain.slot.target.dangling": "entity {id} (not in this hub’s registry)",
  "explain.slot.automation.earlier": "An earlier automation",
  "explain.slot.verb.unknown": "run \"{command}\"",
  "explain.slot.verbPast.unknown": "ran \"{command}\"",
  "explain.slot.verb.null": "act",
  "explain.slot.verbPast.null": "acted",
  "explain.slot.time.unparseable": "an unrecorded time",
  "explain.slot.triggerVerb.null": "changed",
  // whyNot.neverTriggered.*
  "whyNot.neverTriggered.title": "It hasn't run yet.",
  "whyNot.neverTriggered.body": "Nothing has set it off since this automation was loaded. It runs on {triggerSummary}. Nothing is wrong — it's waiting.",
  // explain.chain.*
  "explain.chain.noDetail.title": "This run is on record, but its steps aren't.",
  "explain.chain.noDetail.body": "It happened before the current automations were loaded, so the run was kept but not its steps. Records are never removed.",
  // explain.step.* — HERO-1d D2 (2026-09-13): the do-nothing step (the silent-skip run class), keyed
  // byte-identically from CausalChain.tsx; the hint is the paragraph whole.
  "explain.step.nothing.one": "Nothing was changed: the planned step ended without sending a command.",
  "explain.step.nothing.many": "Nothing was changed: all {count} planned steps ended without sending a command.",
  "explain.step.nothing.hint": "This usually means the devices this automation targets were unavailable, so each was skipped by design. The step-by-step record of these skips is not kept yet.",
  // explain.trigger.*
  "explain.trigger.readingNotRecorded": "{Trigger} set it off at {time} — the reading wasn't recorded.",
  // explain.action.*
  "explain.action.unconfirmed.title": "Sent — the device never confirmed.",
  "explain.action.unconfirmed.body": "The command was sent; no confirmation came back{reasonClause}. It may have worked — the record can't say.",
  "explain.action.pending.title": "Sent — waiting for the device to confirm.",
  "explain.action.pending.body": "Most devices confirm within a second or two. This updates when the device reports.",
  "explain.action.pending.color": "Color changes confirm slowly on some bulbs — this can take several seconds.",
  // explain.mode.*
  "explain.mode.confirmed.label": "Confirmed",
  "explain.mode.confirmed.line": "{Target} {verbPast}.",
  "explain.mode.confirmed.help": "The device's own report confirms it.",
  "explain.mode.heldDispatched.label": "Sent — not settled yet",
  "explain.mode.heldDispatched.line": "{Target} was asked to {verb}; waiting for it to confirm.",
  "explain.mode.heldDispatched.help": "The outcome can still change when the device reports.",
  "explain.mode.timedOut.label": "Sent — no reply",
  "explain.mode.timedOut.line": "{Target} was asked to {verb}; no reply came within its window.",
  "explain.mode.timedOut.help": "The device did not report within the time allowed for this kind of command. It may still have acted.",
  "explain.mode.superseded.label": "Replaced",
  "explain.mode.superseded.line": "{Target} was asked to {verb}, then a newer command replaced it.",
  "explain.mode.superseded.help": "A later command changed the same setting, so this one stopped waiting. This is not a failure.",
  "explain.mode.ackedSilent.label": "Accepted, never confirmed",
  "explain.mode.ackedSilent.line": "{Target} accepted the command to {verb}, but never reported acting.",
  "explain.mode.ackedSilent.help": "Accepting a command is not the same as doing it. No report followed.",
  "explain.mode.ackedSilent.unconfirmable": "This kind of command is acknowledged but never reported back, so it cannot be confirmed.",
  "explain.mode.settledFailed.label": "Failed",
  "explain.mode.settledFailed.line": "The command to {verb} {target} failed{reasonClause}.",
  // HERO-1c correction D2 (SPEC.md:281): a FAILED step that never issued a command (`command` null, a named target).
  "explain.mode.settledFailed.lineNoCommand": "No command was sent to {target} — this step failed{reasonClause}.",
  "explain.mode.settledFailed.help": "The recorded reason says why.",
  "explain.mode.expiredRestart.label": "Expired at restart",
  "explain.mode.expiredRestart.line": "{Target} was asked to {verb}; the hub restarted before it could confirm.",
  "explain.mode.expiredRestart.help": "Waiting does not survive a restart, so the outcome was closed unknown. This is bookkeeping, not a device fault.",
  "explain.mode.skipped.label": "Skipped",
  "explain.mode.skipped.line": "Skipped before any command was sent.",
  "explain.mode.skipped.lineNamed": "Nothing was sent to {target} — this step was skipped.",
  "explain.mode.notRecorded.label": "Not recorded",
  "explain.mode.notRecorded.line": "What happened to {target} isn't recorded.",
  // HERO-1c correction D2 (SPEC.md:280): the not-recorded help, given its key (the sentence unchanged).
  "explain.mode.notRecorded.help": "What happened to this step was not recorded. The step itself is preserved.",
  "explain.mode.unknownOutcome.label": "Recorded as \"{outcome}\"",
  "explain.mode.unknownOutcome.help": "The device reported an outcome this dashboard does not recognise yet — shown as recorded.",
  // explain.terminal.*
  "explain.terminal.completed": "Done in {secs}s.",
  "explain.terminal.completed.open": "Done in {secs}s — {count} outcomes have not settled yet.",
  "explain.terminal.completed.nothing": "Finished in {secs}s, but nothing was changed.",
  "explain.terminal.skipped": "Skipped{reasonClause}.",
  "explain.terminal.failed": "Failed{reasonClause}.",
  "explain.terminal.cancelled": "Cancelled.",
  "explain.terminal.interrupted": "Cut off before it finished.",
  // HERO-1c C0 (D2): SPEC §4's terminal sentence for a completed run with `actionCount` 0, given its key.
  "explain.terminal.noSteps": "Done, recorded no steps.",
  // HERO-1d D1 (2026-09-13): the terminalLine arms that had no row — HEAD's literals, byte for byte.
  // `{notRecorded}` is format.ts NOT_RECORDED (the constant stays a constant; the sentence is keyed);
  // the `.noTime` arms are the HERO-1b honesty row (a missing duration is omitted, never "0.0s");
  // `.status` is HEAD's tail for INTERRUPTED and any status this build does not know — the recorded
  // label's own sentence (`explain.terminal.interrupted` stays unconsumed: a text change is not this lane's).
  "explain.terminal.unrecorded": "Outcome {notRecorded}.",
  "explain.terminal.completed.noTime": "Done.",
  "explain.terminal.completed.nothing.noTime": "Finished, but nothing was changed.",
  "explain.terminal.completed.open.one": "Done in {secs}s — one outcome has not settled yet.",
  "explain.terminal.completed.open.noTime": "Done — {count} outcomes have not settled yet.",
  "explain.terminal.completed.open.one.noTime": "Done — one outcome has not settled yet.",
  "explain.terminal.status": "{label}.",
  // whyNot.headline.*
  "whyNot.headline.conditionNotMet": "It was set off at {time}, but a condition was false, so it didn't act.",
  "whyNot.headline.conditionNotMet.noTime": "It was set off, but a condition was false, so it didn't act.",
  "whyNot.headline.neverTriggered": "It hasn't run yet.",
  "whyNot.headline.neverTriggered.ranFine": "It has run — most recently at {time}.",
  "whyNot.headline.neverTriggered.ranFine.noTime": "It has run. The most recent run is on record.",
  "whyNot.headline.actedButUnconfirmed": "It ran at {time}, but the device never confirmed it acted.",
  // HERO-1c C0 (D3): the N4/N6 no-time arms, given their keys (today rendered by dropping " at {time}").
  "whyNot.headline.actedButUnconfirmed.noTime": "It ran, but the device never confirmed it acted.",
  "whyNot.headline.disabled": "It's turned off, so it can't run.",
  "whyNot.headline.sentNothing": "It ran at {time}, but sent nothing — every step was skipped.",
  "whyNot.headline.sentNothing.noTime": "It ran, but sent nothing — every step was skipped.",
  "whyNot.headline.unknown": "Recorded as \"{verdict}\" — a verdict this dashboard can't explain yet.",
  // whyNot.body.*
  "whyNot.body.conditionNotMet": "It ran on {triggerSummary}, checked its conditions, and one was false. The run shows which.",
  "whyNot.body.actedButUnconfirmed": "The command was sent. No confirmation came back, so whether it worked isn't known.",
  "whyNot.body.disabled": "Turn it on in your automation settings to let it run. When it was turned off isn't recorded.",
  "whyNot.body.sentNothing": "Every step ended without sending a command. Why each was skipped isn't recorded yet.",
  "whyNot.body.ranFine": "Whether anything changed is on the run's own page.",
  // whyNot.link.*
  "whyNot.link.conditionNotMet": "See which condition blocked it →",
  "whyNot.link.ranFine": "See that run — including whether anything changed →",
  "whyNot.link.actedButUnconfirmed": "See the run where the device never confirmed →",
  "whyNot.link.sentNothing": "See the run that sent no commands →",
  // whyNot.kv.*
  "whyNot.kv.trigger": "What would make it run",
  "whyNot.kv.watching": "Watching: {entity}",
  "whyNot.kv.lastChecked": "Last checked",
  "whyNot.kv.lastChecked.unclean": "It ran, but didn't finish cleanly.",
  "whyNot.kv.neverChecked": "Never checked yet.",
  // explain.hub.*
  "explain.hub.title": "Ask your home why",
  "explain.hub.lede": "See what your home did — and what it didn't.",
  "explain.hub.fire.kicker": "Why did",
  "explain.hub.fire.title": "something happen?",
  "explain.hub.fire.text": "See any run step by step — what set it off, what it checked, and whether the device confirmed.",
  "explain.hub.fire.go": "See recent runs →",
  "explain.hub.not.kicker": "Why didn't",
  "explain.hub.not.title": "something happen?",
  "explain.hub.not.text": "Expected a light to come on and it didn't? Find out whether a condition was false, nothing set it off, or the device never confirmed.",
  "explain.hub.not.go": "Diagnose an automation →",
  "explain.hub.autos.noRuns": "No runs yet",
  "explain.hub.autos.noRunsSinceLoad": "Hasn't run since it was loaded",
  // HERO-1d D3 (2026-09-13): the subhead and the two per-automation links HERO-1c filed (the link's
  // `&rsquo;` is this ’, asserted on the rendered text).
  "explain.hub.autos.title": "Your automations",
  "explain.hub.autos.whyFire": "Why did it fire?",
  "explain.hub.autos.whyNot": "Why didn’t it?",
  // explain.whyNot.* / explain.runs.* / explain.run.* — HERO-1d D4 (2026-09-13): the page titles, ledes,
  // the back link and the empty label of the why-not, runs and run pages.
  "explain.whyNot.pick.title": "Why didn't it happen?",
  "explain.whyNot.pick.lede": "Choose the automation you expected to run.",
  "explain.whyNot.title": "Why this didn't happen",
  "explain.whyNot.back": "← Pick another automation",
  "explain.runs.title": "Why did something happen?",
  "explain.runs.lede": "Pick a run to see exactly why it fired, step by step.",
  "explain.runs.empty": "No automation runs yet.",
  "explain.run.title": "Why this happened",
  // explain.permanence.*
  "explain.permanence": "Rebuilt from the permanent activity log. Nothing here is ever deleted, so this run is always here.",
  // explain.nullName.*
  "explain.nullName.note": "This run happened under an earlier version of your automations, so its name is no longer on record. The run itself is preserved.",
  // explain.cascade.*
  "explain.cascade.parentUnrecorded": "Started by another run — which one isn't recorded.",
  "explain.cascade.parent": "← See what triggered this run",
  // explain.trigger.*
  "explain.trigger.detail": "Trigger",
  "explain.trigger.detail.noType": "recorded before the current automations",
  "explain.trigger.detail.noValue": "value not recorded",
  // explain.condition.*
  "explain.condition.line": "The rule \"{condition}\" {verdict}.",
  "explain.condition.atTheTime": "At the time",
  "explain.condition.noReading": "{entity} {attr} had no reading yet.",
  // explain.action.*
  "explain.action.detail.command": "Command",
  "explain.action.detail.reason": "Recorded reason",
  "explain.action.detail.outcome": "Recorded outcome",
  // HERO-1d D2 (2026-09-13): the L2 suffix after a recovered outcome — the leading space is the literal's.
  "explain.action.detail.outcome.recovered": " (recovered from the recorded reason — this record predates the current hub software)",
  // explain.detail.*
  "explain.detail.notRecorded": "not recorded",
  // explain.a11y.*
  "explain.a11y.step": "Step {n} of {N}: {kind} — {label}.",
  "explain.a11y.chain": "Step-by-step explanation, from trigger to outcome",
  "explain.a11y.provisional": "Provisional — may still change",
  "explain.a11y.live": "Updated: {label}",
  // explain.error.*
  "explain.error.title": "This explanation couldn't be loaded.",
  "explain.error.body": "The hub answered with an error. The record itself is safe — try again.",
  "explain.error.retry": "Try again",
  // explain.offline.*
  "explain.offline.title": "The hub can't be reached right now.",
  "explain.offline.body": "Nothing is lost. This page fills in when the connection returns.",
  // explain.replaying.*
  "explain.replaying.title": "The hub is catching up after a restart.",
  "explain.replaying.body": "This takes a moment. Explanations appear as the log is replayed.",
  // explain.loading.*
  "explain.loading": "Loading this run…",
} as const;

type Messages = typeof en;
export type MessageKey = keyof Messages;

const catalogs: Record<typeof locale, Messages> = { en };

/** Resolve a message key to text in the active locale. */
export function t(key: MessageKey): string {
  return catalogs[locale][key];
}
