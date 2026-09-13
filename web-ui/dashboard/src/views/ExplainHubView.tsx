/*
 * ExplainHubView — the front door (`explain.hub.title`): THE differentiator's two questions.
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
import { t } from '../lib/i18n';
import styles from './ExplainHubView.module.css';

export function ExplainHubView() {
  const autos = useApi(() => api.listAutomations());
  // HERO-1c C4 (SPEC §7 `explain.hub.*`; the HERO-1b audit's D7): every sentence on this page
  // is a catalog row behind t(). The two per-automation link texts and the subhead below
  // have no §7 key yet and stay literal (filed in the HERO-1c return).
  return (
    <Page title={t('explain.hub.title')} lede={t('explain.hub.lede')}>
      <div class={styles.questions}>
        <a class={`${styles.q} ${styles.qFire}`} href={href('/explain/runs')}>
          <span class={styles.qKicker}>{t('explain.hub.fire.kicker')}</span>
          <span class={styles.qTitle}>{t('explain.hub.fire.title')}</span>
          <span class={styles.qText}>{t('explain.hub.fire.text')}</span>
          <span class={styles.qGo}>{t('explain.hub.fire.go')}</span>
        </a>

        <a class={`${styles.q} ${styles.qNot}`} href={href('/explain/why-not')}>
          <span class={styles.qKicker}>{t('explain.hub.not.kicker')}</span>
          <span class={styles.qTitle}>{t('explain.hub.not.title')}</span>
          <span class={styles.qText}>{t('explain.hub.not.text')}</span>
          <span class={styles.qGo}>{t('explain.hub.not.go')}</span>
        </a>
      </div>

      <h2 class={styles.subhead}>Your automations</h2>
      <Resource state={autos} labels={{ loading: t('explain.loading'), errorTitle: t('explain.error.title'), errorBody: t('explain.error.body') }}>
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
                    <span class={styles.dim}>{t('explain.hub.autos.noRuns')}</span>
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
