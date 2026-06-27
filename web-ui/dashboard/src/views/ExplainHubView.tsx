/*
 * ExplainHubView — "Ask your home why." THE differentiator's front door.
 * Two co-equal questions as peer entry points (research §4 #1):
 *   - "Why did something happen?"  -> recent runs -> causal chain
 *   - "Why didn't something happen?" -> pick an automation -> non-firing verdict
 * Neither is an afterthought; the second is the most differentiated thing we ship.
 */
import { api } from '../lib/api';
import type { AutomationSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { Page } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './ExplainHubView.module.css';

export function ExplainHubView() {
  const autos = useApi(() => api.listAutomations());
  return (
    <Page title="Ask your home why" lede="Understand what your home did — and just as importantly, what it didn't.">
      <div class={styles.questions}>
        <a class={`${styles.q} ${styles.qFire}`} href={href('/explain/runs')}>
          <span class={styles.qKicker}>Why did</span>
          <span class={styles.qTitle}>something happen?</span>
          <span class={styles.qText}>
            See any automation run, step by step — what set it off, what it checked, and whether the device actually
            confirmed.
          </span>
          <span class={styles.qGo}>See recent runs →</span>
        </a>

        <a class={`${styles.q} ${styles.qNot}`} href={href('/explain/why-not')}>
          <span class={styles.qKicker}>Why didn&rsquo;t</span>
          <span class={styles.qTitle}>something happen?</span>
          <span class={styles.qText}>
            Expected a light to come on and it didn&rsquo;t? Find out whether a condition was false, nothing triggered
            it, or the device never confirmed.
          </span>
          <span class={styles.qGo}>Diagnose an automation →</span>
        </a>
      </div>

      <h2 class={styles.subhead}>Your automations</h2>
      <Resource state={autos}>
        {(rows: AutomationSummary[]) => (
          <ul class={styles.autoList}>
            {rows.map((a) => (
              <li key={a.automationId} class={styles.autoRow}>
                <div class={styles.autoMain}>
                  <span class={styles.autoName}>{a.name}</span>
                  <span class={styles.autoSummary}>
                    {a.components.map((c) => c.summary).join(' · ')}
                  </span>
                </div>
                {a.enabled ? (
                  <StatusPill tone="ok" label="On" size="sm" />
                ) : (
                  <StatusPill tone="unknown" label="Off" size="sm" />
                )}
                <div class={styles.autoLinks}>
                  {a.lastRunId ? (
                    <a href={href(`/explain/run/${a.lastRunId}`)}>Why did it fire?</a>
                  ) : (
                    <span class={styles.dim}>No runs yet</span>
                  )}
                  <a href={href(`/explain/why-not/${a.automationId}`)}>Why didn&rsquo;t it?</a>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Resource>
    </Page>
  );
}
