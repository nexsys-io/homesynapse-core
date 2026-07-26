/*
 * OverviewView — the landing. Answers "is everything OK?" at a glance, features
 * the differentiator ("ask your home why"), and shows recent activity. Glance +
 * Overview density (Doc 13 progressive-disclosure L0/L1).
 */
import { api } from '../lib/api';
import type { EntitySummary, ProjectionStatus, RunSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { runName, runStatusMeta, timeAgo } from '../lib/format';
import { t } from '../lib/i18n';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './OverviewView.module.css';

export function OverviewView() {
  const projection = useApi(() => api.getProjection());
  const runs = useApi(() => api.listRuns({ limit: 5 }));
  const entities = useApi(() => api.listEntities());

  return (
    <Page title="Overview" lede="Your home at a glance." meta={projection.meta}>
      {/* Health banner */}
      <Resource state={projection}>
        {(p: ProjectionStatus) => {
          const live = p.mode === 'LIVE';
          return (
            <div class={`${styles.banner} ${live ? styles.ok : styles.warn}`}>
              <StatusPill tone={live ? 'ok' : 'warn'} label={live ? 'All running' : 'Catching up'} />
              <span class={styles.bannerText}>
                {live ? t('overview.live') : t('overview.catchingUp')}
              </span>
            </div>
          );
        }}
      </Resource>

      {/* The differentiator, featured */}
      <a class={styles.askWhy} href={href('/explain')}>
        <div>
          <div class={styles.askWhyTitle}>Ask your home why</div>
          <div class={styles.askWhySub}>
            See why anything did — or didn&rsquo;t — happen, in plain language. The thing no other smart home can tell
            you.
          </div>
        </div>
        <span class={styles.askWhyGo}>→</span>
      </a>

      <div class={styles.grid}>
        <Card title="Recent runs" aside={<a href={href('/explain/runs')} class={styles.seeAll}>See all</a>}>
          <Resource state={runs}>
            {(rows: RunSummary[]) =>
              rows.length === 0 ? (
                <p class={styles.muted}>No automation runs yet.</p>
              ) : (
                <ul class={styles.runs}>
                  {rows.map((r) => {
                    const m = runStatusMeta(r.status);
                    return (
                      <li key={r.runId}>
                        <a class={styles.runRow} href={href(`/explain/run/${r.runId}`)}>
                          {/* Prior-instance runs carry automationName = null — never a blank. */}
                          <span class={styles.runName}>{runName(r.automationName)}</span>
                          <span class={styles.runWhen}>{timeAgo(r.triggeredAt)}</span>
                          <StatusPill tone={m.tone} label={m.label} size="sm" />
                        </a>
                      </li>
                    );
                  })}
                </ul>
              )
            }
          </Resource>
        </Card>

        {/* G2 — the availability tile renders the HONEST states (Rosonway §5.3):
            "Available" is what the system last CONCLUDED from reports, never a
            live-contact claim (staleAfter can lawfully be null with hours-old
            evidence) — so the tile says "Available" (not "Online"), counts the
            honest UNKNOWN-at-boot state as its own row (never silently absorbed),
            and states what the counts mean. */}
        <Card title="Devices" aside={<a href={href('/devices')} class={styles.seeAll}>See all</a>}>
          <Resource state={entities}>
            {(rows: EntitySummary[]) => {
              const total = rows.length;
              const available = rows.filter((e) => e.availability === 'AVAILABLE').length;
              const offline = rows.filter((e) => e.availability === 'UNAVAILABLE').length;
              const undetermined = rows.filter((e) => e.availability === 'UNKNOWN').length;
              const stale = rows.filter((e) => e.stale).length;
              return (
                <>
                  <dl class="kv">
                    <div class="kvRow">
                      <dt>Available</dt>
                      <dd>{available} of {total}</dd>
                    </div>
                    <div class="kvRow">
                      <dt>Offline</dt>
                      <dd>{offline === 0 ? 'none' : offline}</dd>
                    </div>
                    <div class="kvRow">
                      <dt>Not determined yet</dt>
                      <dd title="Normal right after a restart — settles on each device’s first report.">
                        {undetermined === 0 ? 'none' : undetermined}
                      </dd>
                    </div>
                    <div class="kvRow">
                      <dt>Stale readings</dt>
                      <dd>{stale === 0 ? 'none' : stale}</dd>
                    </div>
                  </dl>
                  <p class={styles.muted} style={{ marginTop: 'var(--hs-space-2)' }}>
                    Counts reflect each device’s last report — not a live connection test. Open a
                    device to see when it was last heard from.
                  </p>
                </>
              );
            }}
          </Resource>
        </Card>
      </div>
    </Page>
  );
}
