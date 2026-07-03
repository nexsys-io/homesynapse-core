/*
 * EventsView — B1 mock. The polled activity feed. Every event shows its ORIGIN
 * (automation / device / you / outside / unknown) — never a silent blank. UNKNOWN
 * is shown honestly as "Unknown", distinguishing "we don't know" from "nothing
 * caused it" (research FM-1). Built against the B1 mock; swaps to real when Core
 * lands GET /api/v1/events.
 */
import { api, ApiProblem } from '../lib/api';
import type { EventSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { clockTime, originMeta, timeAgo } from '../lib/format';
import { t } from '../lib/i18n';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import { EmptyState } from '../components/feedback';
import styles from './EventsView.module.css';

export function EventsView() {
  const state = useApi(() => api.listEvents({ sort: 'DESC', limit: 50 }));

  // The M7.5c live gap, degraded gracefully (FE-1): Core does not serve GET /api/v1/events
  // until M7.5c, so a live backend answers 404 not-found here. That is an EXPECTED state on
  // a current hub — render it as calm teaching, never as an error. (Mock mode always serves
  // the endpoint, so this renders only against a real pre-M7.5c Core.) Keyed on the SLUG
  // (v1.1.1 — the wire `type` is the URI form).
  if (state.status === 'error' && state.error instanceof ApiProblem && state.error.slug === 'not-found') {
    return (
      <Page title="Activity" lede="Recent things that happened in your home, newest first." meta={state.meta}>
        <Card>
          <EmptyState title={t('events.notServedYet.title')} hint={t('events.notServedYet.hint')} />
        </Card>
      </Page>
    );
  }

  return (
    <Page title="Activity" lede="Recent things that happened in your home, newest first." meta={state.meta}>
      <Card pad={false}>
        <Resource state={state}>
          {(events: EventSummary[]) =>
            events.length === 0 ? (
              <p class={styles.empty}>No activity yet.</p>
            ) : (
              <ul class={styles.feed}>
                {events.map((e) => {
                  const o = originMeta(e.origin);
                  return (
                    <li key={e.eventId} class={styles.row}>
                      <time class={styles.time} title={e.occurredAt}>
                        {clockTime(e.occurredAt)}
                      </time>
                      <div class={styles.body}>
                        <span class={styles.summary}>{e.summary}</span>
                        <span class={styles.meta}>{timeAgo(e.occurredAt)}</span>
                      </div>
                      <StatusPill tone={o.tone} label={o.label} title={`Caused ${o.phrase}`} size="sm" />
                    </li>
                  );
                })}
              </ul>
            )
          }
        </Resource>
      </Card>
    </Page>
  );
}
