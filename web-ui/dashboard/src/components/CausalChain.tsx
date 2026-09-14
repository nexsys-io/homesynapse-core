/*
 * CausalChain — THE hero. "Why did this fire?"
 * ---------------------------------------------------------------------------
 * Design rules (from the explainability-UX research + the doctrine):
 *  - Lead with ONE plain, device-backward sentence a stranger reads aloud and is right.
 *  - Render the run as a BOUNDED, linear, ordered list of steps (semantic <ol>, not a
 *    free graph or a role=tree) — legible + robust for screen readers.
 *  - Two disclosure levels only: the plain line (L1) + an expandable <details> with the
 *    technical fact (L2: expression, observedState, params). Never index paths.
 *  - Show command OUTCOME, not just intent — confirmed | sent, not confirmed | failed.
 *  - Lead on "never evicted": the explanation is a projection of the permanent log.
 */
import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useRef } from 'preact/hooks';
import type { CausalAction, CausalChain as Chain } from '../lib/api/contract';
import { StatusPill } from './StatusPill';
import { href } from '../lib/router';
import {
  attrValueList,
  CASCADE_PARENT_UNRECORDED,
  causalSentence,
  clockTimeWithDate,
  commandVerbs,
  danglingTargetLine,
  danglingTriggerLine,
  EMPTY_CHAIN_NOTE,
  heroCopy,
  noReadingLine,
  NOT_RECORDED,
  NULL_NAME_NOTE,
  pendingHint,
  refLabel,
  runStatusMeta,
  triggerVerbFromValue,
  unconfirmableHint,
  UNNAMED_TARGET,
  unrecordedTriggerLine,
  UNRESOLVED_REF_HELP,
  UNRESOLVED_REF_PHRASE,
  UNRESOLVED_REF_PILL,
  type Tone,
} from '../lib/format';
import { UNVERIFIED_RESOLVER, type RefResolver } from '../lib/registry';
import { actionVerdict, isDoNothingRun, type ActionMode } from '../lib/verdicts';
import styles from './CausalChain.module.css';
import { t, type MessageKey } from '../lib/i18n';

export function CausalChain({
  chain,
  resolveRef = UNVERIFIED_RESOLVER,
}: {
  chain: Chain;
  /** FE-HONEST-1 (the §10-J law): registry resolution for every entity ref this
   *  surface renders. A 'dangling' resolution (complete census, id absent)
   *  renders LOUD — the named ULID + "not in this hub's registry", error tone.
   *  Default is the no-claim resolver: without a census, nothing is accused. */
  resolveRef?: RefResolver;
}) {
  /* Live-wire hardening (FE-LIVE-V112 item 1): the wire has served every
   * optional below PRESENT-BUT-NULL beside populated siblings; arrays and
   * sub-objects are guarded the same way so a sparse payload renders honestly
   * instead of throwing. What resolved is shown; what did not says so. */
  const trigger = chain.trigger;
  const conditions = chain.conditions ?? [];
  const actions = chain.actions ?? [];
  const outcome = chain.outcome;
  const doNothing = outcome ? isDoNothingRun(outcome, actions.length) : false;
  // A real, successful, genuinely empty chain: nothing planned, nothing run.
  const genuinelyEmpty =
    conditions.length === 0 && actions.length === 0 && (outcome?.actionCount ?? 0) === 0;
  // HERO-1b B4 (SPEC §4): two empty facts, two sentences. The ERA BOUNDARY — a skeleton
  // chain from before the current automations were loaded (`automationName` null or
  // `trigger.type` null, HERO-0 §1's prior-instance class) — is permanent for these runs:
  // its headline is the "no detail recorded" title and the body stands in for the steps
  // (Q6 ruled (a): this tell now; a Core marker is a v1.1.5 ask). A CURRENT automation
  // that planned nothing keeps the completed.none headline and the empty note.
  const eraSkeleton = genuinelyEmpty && (chain.automationName === null || trigger?.type === null);
  const status = doNothing
    ? ({ label: 'Completed, nothing changed', tone: 'warn' } as const)
    : runStatusMeta(outcome?.status);
  /* HERO-1b B5 (SPEC §5/§8): a held-DISPATCHED action that settles on a later poll is
     announced ONCE through a polite role="status" region (`explain.a11y.live`) — the
     region is always present (so assistive tech is subscribed) and silent otherwise.
     The UI runs no timer: the transition is whatever the hub's next read carries. */
  const verdicts = actions.map((a) => actionVerdict(a));
  const prevProvisional = useRef<boolean[]>([]);
  const settledNow = verdicts.filter((v, i) => prevProvisional.current[i] === true && !v.provisional).map((v) => v.label);
  const liveAnnouncement = settledNow.length > 0 ? heroCopy('explain.a11y.live', { label: settledNow.join(', ') }) : '';
  useLayoutEffect(() => {
    prevProvisional.current = verdicts.map((v) => v.provisional);
  });
  return (
    <div class={styles.wrap}>
      <div role="status" aria-live="polite" class="sr-only">{liveAnnouncement}</div>
      <p class={styles.headline}>{eraSkeleton ? t('explain.chain.noDetail.title') : causalSentence(chain, resolveRef)}</p>

      {/* The null-name class (prior-instance runs): say why calmly, never invent a name. */}
      {chain.automationName === null ? <p class={styles.hint}>{NULL_NAME_NOTE}</p> : null}

      {/* HERO-1b B5 (SPEC §8): the chain is a semantic <ol>; each step carries
          `explain.a11y.step` as visually-hidden text ("Step 2 of 4: action — Confirmed.")
          so the marker's meaning never rides the shape alone; the marker itself is
          aria-hidden. Steps are collected first so every one knows n of N. */}
      <ol class={styles.chain} aria-label={t('explain.a11y.chain')}>
        {(() => {
          const steps: StepSpec[] = [];

          /* Trigger. `type` is null for prior-instance runs — shown honestly as
             "recorded before the current automations", never a blank. `firingValue`
             is OBSERVED NULL on the live wire in all eras — the detail then says
             "value not recorded" in words, never a blank and never "null".
             NEW-6: matchedAt is date-qualified — a run can be days old, and a bare
             clock time on an old run reads as today.
             FE-NULL-1: `subjectRef` is REQUIRED-NULLABLE — null when the triggering event
             is outside the run's correlation (StandardExplanationService:644–:649). The
             line is then the HERO-0 sentence with the recorded time; no label, no
             dangling pill — a null is not an unresolvable id and nothing is accused. */
          {
            const trigId = trigger?.subjectRef?.id;
            const trigRes = resolveRef(trigId);
            const trigDangling = !!trigId && trigRes.kind === 'dangling';
            const trigUnrecorded = trigger?.subjectRef === null;
            // HERO-1b B4 (SPEC §4 "Reading not recorded"): `firingValue` null (today: every run)
            // replaces the trigger LINE with the keyed arm — the L2 detail says "value not
            // recorded" in words. A null subject drops the reading marker (one honest sentence).
            const readingUnrecorded = !trigUnrecorded && !trigDangling && (trigger?.firingValue == null || trigger.firingValue === '');
            steps.push({
              kind: 'trigger',
              tone: trigDangling ? 'error' : 'info',
              marker: trigDangling ? '!' : '●',
              label: trigDangling ? UNRESOLVED_REF_PILL : t('explain.trigger.detail'),
              line: trigDangling
                ? danglingTriggerLine(trigId, triggerVerbFromValue(trigger?.firingValue), clockTimeWithDate(trigger?.matchedAt))
                : trigUnrecorded
                  ? unrecordedTriggerLine(clockTimeWithDate(trigger?.matchedAt))
                  : readingUnrecorded
                    ? heroCopy('explain.trigger.readingNotRecorded', { Trigger: refLabel(trigId, trigRes), time: clockTimeWithDate(trigger?.matchedAt) })
                    : `${refLabel(trigId, trigRes)} ${triggerVerbFromValue(trigger?.firingValue)} at ${clockTimeWithDate(trigger?.matchedAt)}.`,
              pill: trigDangling ? <StatusPill tone="error" label={UNRESOLVED_REF_PILL} title={UNRESOLVED_REF_HELP} size="sm" /> : undefined,
              children: (
                <>
                  {trigDangling ? <p class={styles.hint}>{UNRESOLVED_REF_HELP}</p> : null}
                  <Detail label={t('explain.trigger.detail')}>
                    {trigger?.type ?? t('explain.trigger.detail.noType')} · {trigger?.firingValue ?? t('explain.trigger.detail.noValue')}
                  </Detail>
                </>
              ),
            });
          }

          /* Conditions */
          for (const c of conditions) {
            const tone: Tone = !c.evaluated ? 'unknown' : c.result ? 'ok' : 'warn';
            const verdict = !c.evaluated ? 'was not checked' : c.result ? 'was true' : 'was false';
            const observed = c.observedState ?? [];
            steps.push({
              kind: 'condition',
              tone,
              marker: c.result ? '✓' : c.evaluated ? '✕' : '?',
              label: verdict,
              line: heroCopy('explain.condition.line', { condition: c.expression ?? NOT_RECORDED, verdict }),
              children:
                observed.length > 0 ? (
                  <Detail label={t('explain.condition.atTheTime')}>
                    {observed
                      .map((o) => {
                        const r = resolveRef(o.entityId);
                        const loud = r.kind === 'dangling' ? ` (${UNRESOLVED_REF_PHRASE})` : '';
                        // FE-NULL-1: `value` null = the entity had no value for the attribute at
                        // evaluation (RunExplanation:137) — said in words, never "= null".
                        return o.value === null
                          ? noReadingLine(`${refLabel(o.entityId, r)}${loud}`, o.attribute)
                          : `${refLabel(o.entityId, r)}${loud} ${o.attribute} = ${o.value}`;
                      })
                      .join('; ')}
                  </Detail>
                ) : null,
            });
          }

          /* Actions. Confirmation semantics are MEASURED + ratified (AMD-97): the backend
             owns the per-capability confirm window; the UI renders each honest state as the
             poll delivers it and NEVER runs its own timeout. The hints below are calm,
             class-keyed plain language — no numbers, no timers, no failure-anxiety.

             THE FIVE HONEST FAILURE MODES RENDER DISTINCT (the 2026-07-25 law — the
             distinction IS the product): actionVerdict() consumes the v1.1.2
             `resultOutcome`/`settled` keys first-class where present (SKIP-VIS landed)
             and falls back to recorded-reason recovery only on pre-v1.1.2 payloads.
             Each mode carries its own label + glyph; color reinforces (never hue
             alone). A not-yet-settled outcome renders visibly PROVISIONAL (§5.9) —
             calm, never a settled pill. See lib/verdicts.ts. */
          actions.forEach((a) => {
            const v = actionVerdict(a);
            const targetId = a.targetRef?.id;
            const targetRes = resolveRef(targetId);
            const targetDangling = !!targetId && targetRes.kind === 'dangling';
            // HERO-1c C2: an acked-silent effect-class step carries the §7 `.unconfirmable`
            // sentence as its help (verdicts.actionVerdict), so no second hint repeats it.
            const hint =
              v.mode === 'held-dispatched' ? pendingHint(a.command)
              : v.mode === 'timed-out' ? unconfirmableHint(a.command)
              : null;
            const showHelp =
              v.provisional || v.mode === 'superseded' || v.mode === 'acked-silent' || v.mode === 'expired-restart';
            steps.push({
              kind: 'action',
              tone: targetDangling ? 'error' : v.tone,
              marker: targetDangling ? '!' : '→',
              label: targetDangling ? `${UNRESOLVED_REF_PILL}, ${v.label}` : v.label,
              /* HERO-1c C1 (SPEC §5/§7; the HERO-1b audit's D6): the LINE is the mode's own §7
                 sentence (`explain.mode.<key>.line`, keyed on actionVerdict().mode — see
                 actionStepLine), so a step that never confirmed never reads as done. The
                 HERO-1b null arms keep their sentences: `command` null with no resolvable
                 target (:776) is the skipped sentence, never actionPhrase(null)'s fallback;
                 a dangling target keeps its loud line (pill + help below); a null `targetRef`
                 (:771) keeps the unnamed-target sentence — names no device, accuses none. */
              line:
                a.command === null && (targetDangling || a.targetRef === null)
                  ? heroCopy('explain.mode.skipped.line')
                  : targetDangling
                    ? danglingTargetLine(actionPhrase(a.command), targetId)
                    : a.targetRef === null && v.mode !== 'skipped'
                      ? `${actionPhrase(a.command)} ${UNNAMED_TARGET}.`
                      : actionStepLine(v.mode, a, refLabel(targetId, targetRes)),
              pill: (
                <>
                  {targetDangling ? (
                    <StatusPill tone="error" label={UNRESOLVED_REF_PILL} title={UNRESOLVED_REF_HELP} size="sm" />
                  ) : null}
                  <StatusPill
                    tone={v.tone}
                    label={v.label}
                    title={v.help}
                    size="sm"
                    glyph={v.glyph}
                    provisional={v.provisional}
                  />
                </>
              ),
              children: (
                <>
                  {targetDangling ? <p class={styles.hint}>{UNRESOLVED_REF_HELP}</p> : null}
                  {showHelp ? <p class={styles.hint}>{v.help}</p> : null}
                  {hint ? <p class={styles.hint}>{hint}</p> : null}
                  <Detail label={t('explain.action.detail.command')}>{a.command ?? NOT_RECORDED}{attrValueList(a.params)}</Detail>
                  {a.reason ? <Detail label={t('explain.action.detail.reason')}>{a.reason}</Detail> : null}
                  {v.resultOutcome ? (
                    <Detail label={t('explain.action.detail.outcome')}>
                      {v.resultOutcome}
                      {v.recovered ? t('explain.action.detail.outcome.recovered') : ''}
                    </Detail>
                  ) : null}
                </>
              ),
            });
          });

          /* The silent-skip run class (lawful Doc 07 §3.9 per-target skips): planned
             actions, zero commands, empty actions[] — nothing visible happened and
             today no marker event records why. Render it honestly, never as clean
             success; the actionCount-vs-actions[] disagreement is the tell. */
          if (doNothing && outcome) {
            steps.push({
              kind: 'action',
              tone: 'warn',
              marker: '→',
              label: status.label,
              // HERO-1d D2: the step's line and its hint are §7 rows (`explain.step.nothing.*`), byte-identical.
              line: outcome.actionCount === 1 ? t('explain.step.nothing.one') : heroCopy('explain.step.nothing.many', { count: String(outcome.actionCount) }),
              children: <p class={styles.hint}>{t('explain.step.nothing.hint')}</p>,
            });
          }

          /* The honest EMPTY state — two facts, two sentences (HERO-1b B4, SPEC §4):
             the era boundary renders the "no detail recorded" body in place of the
             steps; a current automation that planned nothing renders the explicit
             empty note. Never a silent blank, never an error posture — nothing failed. */
          if (eraSkeleton) {
            steps.push({ kind: 'empty', tone: 'unknown', marker: '○', label: status.label, line: t('explain.chain.noDetail.body') });
          } else if (genuinelyEmpty) {
            steps.push({ kind: 'empty', tone: 'unknown', marker: '○', label: status.label, line: EMPTY_CHAIN_NOTE });
          }

          /* Terminal outcome */
          steps.push({
            kind: 'outcome',
            tone: status.tone,
            marker: '◆',
            label: status.label,
            line: terminalLine(chain),
            pill: <StatusPill tone={status.tone} label={status.label} size="sm" />,
          });

          return steps.map((s, i) => <Step key={i} n={i + 1} N={steps.length} {...s} />);
        })()}
      </ol>
      {/* FE-NULL-1 / HERO-0 F4: `parentRunId` is ALWAYS null in V1 (RunExplanation:213–:219)
          — a null is NOT "root". depth > 0 with no parent id says so honestly (no link);
          depth 0 renders nothing, as before; a parent id (a later Core) keeps the link. */}
      {chain.cascade?.parentRunId ? (
        <p class={styles.cascade}>
          <a href={href(`/explain/run/${chain.cascade.parentRunId}`)}>{t('explain.cascade.parent')}</a>
        </p>
      ) : (chain.cascade?.depth ?? 0) > 0 ? (
        <p class={styles.cascade}>{CASCADE_PARENT_UNRECORDED}</p>
      ) : null}

      <p class={styles.permanence}>{t('explain.permanence')}</p>
    </div>
  );
}

interface StepSpec {
  tone: Tone;
  marker: string;
  line: string;
  /** The state the marker carries, in words — read to screen readers through
   *  `explain.a11y.step` (SPEC §8): the step's index and count, its kind, then this label. */
  label: string;
  pill?: ComponentChildren;
  kind: string;
  children?: ComponentChildren;
}

function Step({ tone, marker, line, label, pill, kind, children, n, N }: StepSpec & { n: number; N: number }) {
  return (
    <li class={styles.step} data-kind={kind}>
      <span class="sr-only">{heroCopy('explain.a11y.step', { n: String(n), N: String(N), kind, label })}</span>
      <span class={`${styles.marker} ${styles[`tone_${tone}`]}`} aria-hidden="true">
        {marker}
      </span>
      <div class={styles.stepBody}>
        <div class={styles.stepLine}>
          <span>{line}</span>
          {pill}
        </div>
        {children ? <div class={styles.details}>{children}</div> : null}
      </div>
    </li>
  );
}

function Detail({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <details class={styles.detail}>
      <summary class={styles.summary}>{label}</summary>
      <div class={styles.detailBody}>{children}</div>
    </details>
  );
}

/* The trigger phrase is format.triggerVerbFromValue — null-hardened there
 * (OBSERVED NULL on the live wire in all eras); the former local duplicate of
 * that logic was the second `.toLowerCase()` crash site and is removed. */
function actionPhrase(command: string | null | undefined): string {
  switch (command) {
    case 'turn_on':
      return 'Turned on';
    case 'turn_off':
      return 'Turned off';
    case 'dim':
      return 'Dimmed';
    default:
      // Present-but-null guard: never "Ran null on" — say what is known.
      return command ? `Ran ${command} on` : 'Ran an unrecorded command on';
  }
}
/* HERO-1c C1 (SPEC §5/§7; the HERO-1b audit's D6): the action step LINE per confirmation
 * mode — each `explain.mode.<key>.line` filled at the resolved label (never lower-cased:
 * `{Target}` and `{target}` are the same name), the command's verbs (format.commandVerbs,
 * with the §7 null arms) and the recorded reason as " — {reason}" or "". The skipped pair
 * keys on the target: `skipped.line` with none, `skipped.lineNamed` with one. */
const MODE_LINE_KEY: Record<Exclude<ActionMode, 'skipped'>, MessageKey> = {
  confirmed: 'explain.mode.confirmed.line',
  'held-dispatched': 'explain.mode.heldDispatched.line',
  'timed-out': 'explain.mode.timedOut.line',
  superseded: 'explain.mode.superseded.line',
  'acked-silent': 'explain.mode.ackedSilent.line',
  'settled-failed': 'explain.mode.settledFailed.line',
  'expired-restart': 'explain.mode.expiredRestart.line',
  'not-recorded': 'explain.mode.notRecorded.line',
};
function actionStepLine(mode: ActionMode, a: CausalAction, target: string): string {
  if (mode === 'skipped') {
    return a.targetRef === null ? heroCopy('explain.mode.skipped.line') : heroCopy('explain.mode.skipped.lineNamed', { target });
  }
  const reasonClause = a.reason ? ` — ${a.reason}` : '';
  // HERO-1c correction D2 (SPEC §7 :281): a FAILED step that never issued a command (:776) says so —
  // never the null verb's "to act".
  if (mode === 'settled-failed' && a.command === null) return heroCopy('explain.mode.settledFailed.lineNoCommand', { target, reasonClause });
  const { verb, verbPast } = commandVerbs(a.command);
  return heroCopy(MODE_LINE_KEY[mode], { Target: target, target, verb, verbPast, reasonClause });
}
/* HERO-1d D1 (2026-09-13): every arm of the terminal line is a §7 `explain.terminal.*` row read through
 * heroCopy(), byte-identical to the literal it replaced. `{notRecorded}` carries the NOT_RECORDED constant;
 * `{secs}` is omitted with the whole clause when the record has no duration (the HERO-1b honesty row — the
 * `.noTime` arms exist for exactly that, never "0.0s"); `{reasonClause}` is " — {reason}" or "". The tail
 * (INTERRUPTED and any status this build does not know) is HEAD's `${runStatusMeta(s).label}.` sentence,
 * keyed as `.status` — `explain.terminal.interrupted` ("Cut off before it finished.") stays unconsumed,
 * since a text change is not this lane's. */
function terminalLine(chain: Chain): string {
  const outcome = chain.outcome;
  if (!outcome) return heroCopy('explain.terminal.unrecorded', { notRecorded: NOT_RECORDED });
  const actions = chain.actions ?? [];
  const s = outcome.status;
  // durationMs guarded: a missing duration is omitted honestly, never "0.0s"
  // (a plausible-looking number the record does not actually carry).
  const secs =
    typeof outcome.durationMs === 'number' && Number.isFinite(outcome.durationMs)
      ? (outcome.durationMs / 1000).toFixed(1)
      : null;
  const reasonClause = outcome.reason ? ` — ${outcome.reason}` : '';
  if (s === 'COMPLETED') {
    // A do-nothing run must never read as clean success (the silent-skip class).
    if (isDoNothingRun(outcome, actions.length)) {
      return secs ? heroCopy('explain.terminal.completed.nothing', { secs }) : heroCopy('explain.terminal.completed.nothing.noTime');
    }
    // §5.9 honesty: a COMPLETED run's action outcome can settle AFTER the run
    // finishes (a late report re-derives on the next read) — while any action is
    // still unsettled, the terminal line must not read as the final word.
    const open = actions.filter((a) => actionVerdict(a).provisional).length;
    if (open > 0) {
      const count = String(open);
      if (open === 1) return secs ? heroCopy('explain.terminal.completed.open.one', { secs }) : heroCopy('explain.terminal.completed.open.one.noTime');
      return secs ? heroCopy('explain.terminal.completed.open', { secs, count }) : heroCopy('explain.terminal.completed.open.noTime', { count });
    }
    return secs ? heroCopy('explain.terminal.completed', { secs }) : heroCopy('explain.terminal.completed.noTime');
  }
  if (s === 'SKIPPED') return heroCopy('explain.terminal.skipped', { reasonClause });
  if (s === 'FAILED') return heroCopy('explain.terminal.failed', { reasonClause });
  if (s === 'CANCELLED') return heroCopy('explain.terminal.cancelled');
  // Open-vocabulary hardening (the closed-switch class): INTERRUPTED and any status this build does
  // not know render the recorded label's own sentence — shown as recorded, never a crash, never a guess.
  return heroCopy('explain.terminal.status', { label: runStatusMeta(s).label });
}
