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
import type { CausalChain as Chain } from '../lib/api/contract';
import { StatusPill } from './StatusPill';
import { href } from '../lib/router';
import {
  attrValueList,
  causalSentence,
  clockTime,
  labelFor,
  NULL_NAME_NOTE,
  outcomeMeta,
  pendingHint,
  runStatusMeta,
  unconfirmableHint,
  type Tone,
} from '../lib/format';
import { classifyRecordedReason, isDoNothingRun, resultOutcomeMeta } from '../lib/verdicts';
import styles from './CausalChain.module.css';
import { t } from '../lib/i18n';

export function CausalChain({ chain }: { chain: Chain }) {
  const doNothing = isDoNothingRun(chain.outcome, chain.actions.length);
  const status = doNothing
    ? ({ label: 'Completed, nothing changed', tone: 'warn' } as const)
    : runStatusMeta(chain.outcome.status);
  return (
    <div class={styles.wrap}>
      <p class={styles.headline}>{causalSentence(chain)}</p>

      {/* The null-name class (prior-instance runs): say why calmly, never invent a name. */}
      {chain.automationName === null ? <p class={styles.hint}>{NULL_NAME_NOTE}</p> : null}

      <ol class={styles.chain} aria-label="Step-by-step explanation, from trigger to outcome">
        {/* Trigger. `type` is null for prior-instance runs — shown honestly as
            "recorded before the current automations", never a blank. */}
        <Step kind="trigger" tone="info" marker="●" line={`${labelFor(chain.trigger.subjectRef.id)} ${triggerPhrase(chain.trigger.firingValue)} at ${clockTime(chain.trigger.matchedAt)}.`}>
          <Detail label="Trigger">
            {chain.trigger.type ?? 'recorded before the current automations'} · {chain.trigger.firingValue}
          </Detail>
        </Step>

        {/* Conditions */}
        {chain.conditions.map((c, i) => {
          const tone: Tone = !c.evaluated ? 'unknown' : c.result ? 'ok' : 'warn';
          const verdict = !c.evaluated ? 'was not checked' : c.result ? 'was true' : 'was false';
          return (
            <Step
              key={i}
              kind="condition"
              tone={tone}
              marker={c.result ? '✓' : c.evaluated ? '✕' : '?'}
              line={`The rule "${c.expression}" ${verdict}.`}
            >
              {c.observedState.length > 0 ? (
                <Detail label="At the time">
                  {c.observedState.map((o) => `${labelFor(o.entityId)} ${o.attribute} = ${o.value}`).join('; ')}
                </Detail>
              ) : null}
            </Step>
          );
        })}

        {/* Actions. Confirmation semantics are MEASURED + ratified (AMD-97): the backend
            owns the per-capability confirm window; the UI renders each honest state as the
            poll delivers it and NEVER runs its own timeout. The hints below are calm,
            class-keyed plain language — no numbers, no timers, no failure-anxiety.

            VERDICT HONESTY (the ten-value vocabulary): Core's explanation service
            currently flattens every non-acknowledged command_result into FAILED
            (StandardExplanationService.isFailure) — but the RECORDED REASON still
            deterministically identifies the deliberately-superseded and restart-
            accounting dispositions. Those render with their own calm treatment
            (an intent change / honest bookkeeping is NOT a failure), with the
            recorded reason shown as provenance. See lib/verdicts.ts. */}
        {chain.actions.map((a, i) => {
          const disposition = a.outcome === 'FAILED' ? classifyRecordedReason(a.reason) : null;
          const vm = disposition ? resultOutcomeMeta(disposition) : null;
          const om = outcomeMeta(a.outcome);
          const tone = vm ? vm.tone : om.tone;
          const hint =
            a.outcome === 'DISPATCHED' ? pendingHint(a.command)
            : a.outcome === 'UNCONFIRMED' ? unconfirmableHint(a.command)
            : null;
          return (
            <Step
              key={i}
              kind="action"
              tone={tone}
              marker="→"
              line={`${actionPhrase(a.command)} ${labelFor(a.targetRef.id)}.`}
              pill={
                vm ? (
                  <StatusPill tone={vm.tone} label={vm.label} title={vm.help} size="sm" />
                ) : (
                  <StatusPill tone={om.tone} label={om.label} title={om.help} size="sm" />
                )
              }
            >
              {vm ? <p class={styles.hint}>{vm.help}</p> : null}
              {hint ? <p class={styles.hint}>{hint}</p> : null}
              <Detail label="Command">{a.command}{attrValueList(a.params)}</Detail>
              {a.reason ? <Detail label="Recorded reason">{a.reason}</Detail> : null}
            </Step>
          );
        })}

        {/* The silent-skip run class (lawful Doc 07 §3.9 per-target skips): planned
            actions, zero commands, empty actions[] — nothing visible happened and
            today no marker event records why. Render it honestly, never as clean
            success; the actionCount-vs-actions[] disagreement is the tell. */}
        {isDoNothingRun(chain.outcome, chain.actions.length) ? (
          <Step
            kind="action"
            tone="warn"
            marker="→"
            line={`Nothing was changed: ${chain.outcome.actionCount === 1 ? 'the planned step' : `all ${chain.outcome.actionCount} planned steps`} ended without sending a command.`}
          >
            <p class={styles.hint}>
              This usually means the devices this automation targets were unavailable, so each was
              skipped by design. The step-by-step record of these skips is not kept yet.
            </p>
          </Step>
        ) : null}

        {/* Terminal outcome */}
        <Step
          kind="outcome"
          tone={status.tone}
          marker="◆"
          line={terminalLine(chain)}
          pill={<StatusPill tone={status.tone} label={status.label} size="sm" />}
        />
      </ol>

      {chain.cascade.parentRunId ? (
        <p class={styles.cascade}>
          <a href={href(`/explain/run/${chain.cascade.parentRunId}`)}>← See what triggered this run</a>
        </p>
      ) : null}

      <p class={styles.permanence}>{t('hero.permanence')}</p>
    </div>
  );
}

function Step({
  tone,
  marker,
  line,
  pill,
  kind,
  children,
}: {
  tone: Tone;
  marker: string;
  line: string;
  pill?: ComponentChildren;
  kind: string;
  children?: ComponentChildren;
}) {
  return (
    <li class={styles.step} data-kind={kind}>
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

function triggerPhrase(firingValue: string): string {
  const v = firingValue.toLowerCase();
  if (v.includes('motion')) return 'detected motion';
  if (v.includes('open')) return 'was opened';
  if (v.includes('close')) return 'was closed';
  return `changed (${firingValue})`;
}
function actionPhrase(command: string): string {
  switch (command) {
    case 'turn_on':
      return 'Turned on';
    case 'turn_off':
      return 'Turned off';
    case 'dim':
      return 'Dimmed';
    default:
      return `Ran ${command} on`;
  }
}
function terminalLine(chain: Chain): string {
  const s = chain.outcome.status;
  const secs = (chain.outcome.durationMs / 1000).toFixed(1);
  if (s === 'COMPLETED') {
    // A do-nothing run must never read as clean success (the silent-skip class).
    return isDoNothingRun(chain.outcome, chain.actions.length)
      ? `Finished in ${secs}s, but nothing was changed.`
      : `Done in ${secs}s.`;
  }
  if (s === 'SKIPPED') return chain.outcome.reason ? `Skipped — ${chain.outcome.reason}.` : 'Skipped.';
  if (s === 'FAILED') return chain.outcome.reason ? `Failed — ${chain.outcome.reason}.` : 'Failed.';
  return `${runStatusMeta(s).label}.`;
}
