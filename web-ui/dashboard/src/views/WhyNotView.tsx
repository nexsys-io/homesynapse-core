/*
 * WhyNotView — the co-equal hero half: "why didn't it fire?" (B3 mock).
 * The single most differentiated read in the field: no competitor distinguishes
 * condition-false vs trigger-never-matched vs device-didn't-act. We present the
 * verdict as ONE plain sentence + the one gating fact + a next step (research §4).
 * Pick-an-automation mode when no id is supplied.
 */
import { api } from '../lib/api';
import type { AutomationSummary, NonFiringExplanation } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { verdictMeta, clockTime } from '../lib/format';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './WhyNotView.module.css';

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
  return (
    <Page title="Why this didn't happen" meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/why-not')}>← Pick another automation</a>
      </p>
      <Resource state={state}>
        {(nf: NonFiringExplanation) => {
          const v = verdictMeta(nf.verdict);
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
                    <dd class={styles.left}>{nf.triggerSummary}</dd>
                  </div>
                  {nf.lastEvaluation.at ? (
                    <div class="kvRow">
                      <dt>Last checked</dt>
                      <dd>
                        {clockTime(nf.lastEvaluation.at)}
                        {nf.lastEvaluation.conditionsResult ? ` · ${nf.lastEvaluation.conditionsResult}` : ''}
                      </dd>
                    </div>
                  ) : null}
                </dl>

                {nf.verdict === 'DISABLED' ? (
                  <p class={styles.nextStep}>To let it run, turn this automation on in your automation settings.</p>
                ) : null}
                {nf.verdict === 'ACTED_BUT_UNCONFIRMED' && nf.lastRelevantRunId ? (
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
