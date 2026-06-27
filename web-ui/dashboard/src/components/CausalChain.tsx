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
  outcomeMeta,
  runStatusMeta,
  type Tone,
} from '../lib/format';
import styles from './CausalChain.module.css';

export function CausalChain({ chain }: { chain: Chain }) {
  const status = runStatusMeta(chain.outcome.status);
  return (
    <div class={styles.wrap}>
      <p class={styles.headline}>{causalSentence(chain)}</p>

      <ol class={styles.chain} aria-label="Step-by-step explanation, from trigger to outcome">
        {/* Trigger */}
        <Step kind="trigger" tone="info" marker="●" line={`${labelFor(chain.trigger.subjectRef.id)} ${triggerPhrase(chain.trigger.firingValue)} at ${clockTime(chain.trigger.matchedAt)}.`}>
          <Detail label="Trigger">{chain.trigger.type} · {chain.trigger.firingValue}</Detail>
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

        {/* Actions */}
        {chain.actions.map((a, i) => {
          const om = outcomeMeta(a.outcome);
          return (
            <Step
              key={i}
              kind="action"
              tone={om.tone}
              marker="→"
              line={`${actionPhrase(a.command)} ${labelFor(a.targetRef.id)}.`}
              pill={<StatusPill tone={om.tone} label={om.label} title={om.help} size="sm" />}
            >
              <Detail label="Command">{a.command}{attrValueList(a.params)}</Detail>
              {a.reason ? <Detail label="Note">{a.reason}</Detail> : null}
            </Step>
          );
        })}

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

      <p class={styles.permanence}>
        This explanation is rebuilt from HomeSynapse&rsquo;s permanent activity log — it is never deleted, so the run
        you need is always here.
      </p>
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
  if (s === 'COMPLETED') return `Done in ${secs}s.`;
  if (s === 'SKIPPED') return chain.outcome.reason ? `Skipped — ${chain.outcome.reason}.` : 'Skipped.';
  if (s === 'FAILED') return chain.outcome.reason ? `Failed — ${chain.outcome.reason}.` : 'Failed.';
  return `${runStatusMeta(s).label}.`;
}
