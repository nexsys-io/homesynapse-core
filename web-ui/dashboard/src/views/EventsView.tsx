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
import { clockTimeWithDate, originMeta, timeAgo } from '../lib/format';
import { t } from '../lib/i18n';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import { EmptyState } from '../components/feedback';
import styles from './EventsView.module.css';

export function EventsView() {
  const state = useApi(() => api.listEvents({ sort: 'DESC', limit: 50 }));

  // The M7.5c live gap, degraded gracefully (FE-1): Core does not serve GET /api/v1/events
  // until M7.5c, so a live hub answers a ROUTER-level 404 here. That is an EXPECTED state on
  // a current hub — render it as calm teaching, never as an error. (Mock mode always serves
  // the endpoint, so this renders only against a real pre-M7.5c Core.)
  // NEW-7 (a): keyed on the OBSERVED wire discriminator (path ∧ 404 ∧ application/json-not-
  // problem+json — client.ts `isUnservedEndpoint404`, sitting record 2026-08-20 §6 row 5).
  // The earlier `slug === 'not-found'` keying was an inference the live wire refuted (the
  // rehearsal 404 rendered the generic card): a problem+json `not-found` would mean the
  // endpoint EXISTS and something was not found — that case now keeps the honest generic
  // card + Try again, as every OTHER 404 does.
  if (state.status === 'error' && state.error instanceof ApiProblem && state.error.isUnservedEndpoint) {
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
                      {/* NEW-6: date-qualified — the feed can span days. */}
                      <time class={styles.time} title={e.occurredAt}>
                        {clockTimeWithDate(e.occurredAt)}
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
