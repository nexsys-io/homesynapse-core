/*
 * EventsView — B1 mock. The polled activity feed. Every event shows its ORIGIN
 * (automation / device / you / outside / unknown) — never a silent blank. UNKNOWN
 * is shown honestly as "Unknown", distinguishing "we don't know" from "nothing
 * caused it" (research FM-1). Built against the B1 mock; swaps to real when Core
 * lands GET /api/v1/events.
 */
import { api } from '../lib/api';
import type { EventSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { clockTime, originMeta, timeAgo } from '../lib/format';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './EventsView.module.css';

export function EventsView() {
  const state = useApi(() => api.listEvents({ sort: 'DESC', limit: 50 }));
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
