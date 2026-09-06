/*
 * WhyNotView — the co-equal hero half: "why didn't it fire?" (B3 mock).
 * The single most differentiated read in the field: no competitor distinguishes
 * condition-false vs trigger-never-matched vs device-didn't-act. We present the
 * verdict as ONE plain sentence + the one gating fact + a next step (research §4).
 * Pick-an-automation mode when no id is supplied.
 */
import { api } from '../lib/api';
import type { AutomationSummary, NonFiringExplanation, SubjectRef } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import {
  verdictMeta,
  clockTimeWithDate,
  refLabel,
  UNRESOLVED_REF_HELP,
  UNRESOLVED_REF_PHRASE,
  UNRESOLVED_REF_PILL,
} from '../lib/format';
import { useRefResolver, type RefResolver } from '../lib/registry';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './WhyNotView.module.css';

/** v1.1.3 (FE-113 / CG-1): the trigger's entity, rendered THROUGH the registry
 *  census (FE-HONEST-1 §10-J). Resolved → the display name, linked to the device
 *  list; dangling on a COMPLETE census → LOUD: the ULID verbatim, "not in this
 *  hub's registry", the failing pill (exactly the causal chain's render); an
 *  incomplete/unloaded census → the neutral label, no accusation. Callers pass
 *  only a PRESENT-object ref — null and absent render the sentence alone. */
function TriggerEntity({ subjectRef, resolveRef }: { subjectRef: SubjectRef; resolveRef: RefResolver }) {
  const res = resolveRef(subjectRef.id);
  if (res.kind === 'dangling') {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--hs-space-2)', flexWrap: 'wrap' }}>
        <span>
          <span style={{ fontFamily: 'var(--hs-font-mono, monospace)' }}>entity {subjectRef.id}</span> — {UNRESOLVED_REF_PHRASE}
        </span>
        <StatusPill tone="error" label={UNRESOLVED_REF_PILL} title={UNRESOLVED_REF_HELP} size="sm" />
      </span>
    );
  }
  return <a href={href(`/devices/${encodeURIComponent(subjectRef.id)}`)}>{refLabel(subjectRef.id, res)}</a>;
}

export function WhyNotView({ automationId }: { automationId?: string }) {
  if (!automationId) return <WhyNotPicker />;
  return <WhyNotDetail automationId={automationId} />;
}

function WhyNotPicker() {
  const autos = useApi(() => api.listAutomations());
  return (
    <Page title="Why didn't it happen?" lede="Choose the automation you expected to run.">
      <Resource state={autos}>
        {(rows: AutomationSummary[]) => (
          <ul class={styles.pick}>
            {rows.map((a) => (
              <li key={a.automationId}>
                <a class={styles.pickRow} href={href(`/explain/why-not/${a.automationId}`)}>
                  <span class={styles.pickName}>{a.name}</span>
                  <span class={styles.pickSummary}>{a.components.map((c) => c.summary).join(' · ')}</span>
                </a>
              </li>
            ))}
          </ul>
        )}
      </Resource>
    </Page>
  );
}

function WhyNotDetail({ automationId }: { automationId: string }) {
  const state = useApi(() => api.getNonFiring(automationId));
  // v1.1.3: the registry census for the trigger ref (the same one-poll-loop read the
  // causal chain uses); 'unverified' until it is in — nothing is accused without it.
  const resolveRef = useRefResolver();
  return (
    <Page title="Why this didn't happen" meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/why-not')}>← Pick another automation</a>
      </p>
      <Resource state={state}>
        {(nf: NonFiringExplanation) => {
          // DP-B2 (core's ruled shape): the frozen 4-value verdict has no "fired
          // fine" value, so a clean recent run arrives as NEVER_TRIGGERED with a
          // NON-NULL lastRelevantRunId — the run id is how the wire says "it did
          // run, and confirmed". Tell the two apart here (core's stated intent:
          // "the UI tells them apart by the non-null run id").
          const ranFine = nf.verdict === 'NEVER_TRIGGERED' && nf.lastRelevantRunId !== null;
          // v1.1.2 (SKIP-VIS DP-2): the silent-skip marker. TRUE exactly when the
          // governing COMPLETED run issued zero device commands — a do-nothing run
          // is NEVER presented as clean success; it gets its own honest verdict pill.
          const sentNothing = nf.noCommandsIssued === true;
          const v = sentNothing
            ? ({ label: 'Ran, but sent nothing', tone: 'warn' } as const)
            : ranFine
              ? ({ label: 'It did run', tone: 'ok' } as const)
              : verdictMeta(nf.verdict);
          return (
            <Card>
              <div class={styles.detail}>
                <div class={styles.verdictRow}>
                  <StatusPill tone={v.tone} label={v.label} />
                  <span class={styles.autoName}>{nf.automationName}</span>
                </div>

                <p class={styles.explanation}>{nf.explanation}</p>

                <dl class="kv">
                  <div class="kvRow">
                    <dt>What would make it run</dt>
                    <dd class={styles.left}>
                      {nf.triggerSummary}
                      {/* v1.1.3 (FE-113 / CG-1): the R-4 concealment closed on THIS surface —
                          the wire can now name WHICH entity the trigger watches. Rendered
                          only for a PRESENT-object `triggerRef`, through the registry census
                          (resolved → name; dangling → LOUD). PRESENT-null ("names no single
                          entity") and ABSENT (a pre-v1.1.3 hub) both render the sentence
                          alone — two honest facts, neither claims a name. */}
                      {nf.triggerRef ? (
                        <span style={{ display: 'block', marginTop: 'var(--hs-space-1)', fontSize: 'var(--hs-text-sm)' }}>
                          Watching: <TriggerEntity subjectRef={nf.triggerRef} resolveRef={resolveRef} />
                        </span>
                      ) : null}
                    </dd>
                  </div>
                  {/* OBSERVED LIVE NULLABILITY (2026-08-16, §4.5): the wire serves
                      `lastEvaluation: null` — the null case renders ABSENCE (the
                      row simply doesn't render), never fabrication, never a
                      throw. This exact dereference, unguarded, was DX-16's crash
                      (`can't access property "at"`). Date-qualified stamp per
                      NEW-6: an evaluation can be >24 h old. */}
                  {nf.lastEvaluation?.at ? (
                    <div class="kvRow">
                      <dt>Last checked</dt>
                      <dd>
                        {clockTimeWithDate(nf.lastEvaluation.at)}
                        {nf.lastEvaluation.conditionsResult ? ` · ${nf.lastEvaluation.conditionsResult}` : ''}
                      </dd>
                    </div>
                  ) : null}
                </dl>

                {ranFine && nf.lastRelevantRunId ? (
                  <p class={styles.nextStep}>
                    {/* Honest caveat: a run that lawfully skipped every unavailable
                        target still reports "ran" here — the run's own page shows
                        whether anything actually changed. */}
                    <a href={href(`/explain/run/${nf.lastRelevantRunId}`)}>
                      See that run — including whether anything actually changed →
                    </a>
                  </p>
                ) : null}
                {nf.verdict === 'DISABLED' ? (
                  <p class={styles.nextStep}>To let it run, turn this automation on in your automation settings.</p>
                ) : null}
                {sentNothing && nf.lastRelevantRunId ? (
                  <p class={styles.nextStep}>
                    {/* The silent-skip truth, one click away — the run page shows the
                        do-nothing record honestly (never a clean success tile). */}
                    <a href={href(`/explain/run/${nf.lastRelevantRunId}`)}>
                      See the run that sent no commands →
                    </a>
                  </p>
                ) : null}
                {!sentNothing && nf.verdict === 'ACTED_BUT_UNCONFIRMED' && nf.lastRelevantRunId ? (
                  <p class={styles.nextStep}>
                    <a href={href(`/explain/run/${nf.lastRelevantRunId}`)}>See the run where the device never confirmed →</a>
                  </p>
                ) : null}
                {nf.verdict === 'CONDITION_NOT_MET' && nf.lastRelevantRunId ? (
                  <p class={styles.nextStep}>
                    <a href={href(`/explain/run/${nf.lastRelevantRunId}`)}>See exactly which condition blocked it →</a>
                  </p>
                ) : null}
              </div>
            </Card>
          );
        }}
      </Resource>
    </Page>
  );
}
