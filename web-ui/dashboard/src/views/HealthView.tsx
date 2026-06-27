/*
 * HealthView — composes A4 /internal/projection + A5 /internal/dlq (both real).
 * Answers "is the system live or catching up, and is anything wedged?" in plain
 * language. (B2 /api/v1/health is a future convenience consolidation; until then
 * we compose the two A-class reads, which is the frozen-contract guidance §B2.)
 */
import { api } from '../lib/api';
import type { ProjectionMode } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import type { Tone } from '../lib/format';

function projectionMeta(mode: ProjectionMode): { tone: Tone; label: string; plain: string } {
  switch (mode) {
    case 'LIVE':
      return { tone: 'ok', label: 'Live', plain: 'HomeSynapse is up to date and processing events in real time.' };
    case 'TRANSITION':
      return { tone: 'warn', label: 'Catching up', plain: 'Almost there — finishing catch-up after a restart.' };
    case 'REPLAY':
      return { tone: 'warn', label: 'Starting up', plain: 'Rebuilding current state from the activity log.' };
  }
}

export function HealthView() {
  const projection = useApi(() => api.getProjection());
  const dlq = useApi(() => api.getDlq());

  return (
    <Page title="System health" lede="A quick read on whether everything is working." meta={projection.meta}>
      <Card title="Live status">
        <Resource state={projection}>
          {(p) => {
            const m = projectionMeta(p.mode);
            return (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hs-space-3)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--hs-space-2)' }}>
                  <StatusPill tone={m.tone} label={m.label} />
                  <span style={{ color: 'var(--hs-text-secondary)', fontSize: 'var(--hs-text-sm)' }}>{m.plain}</span>
                </div>
                <dl class="kv">
                  <div class="kvRow">
                    <dt>Behind by</dt>
                    <dd>{p.lagEvents === 0 ? 'nothing — fully caught up' : `${p.lagEvents} events`}</dd>
                  </div>
                  <div class="kvRow">
                    <dt>Projection version</dt>
                    <dd>{p.projectionVersion}</dd>
                  </div>
                  <div class="kvRow">
                    <dt>Activity position</dt>
                    <dd>{p.viewPosition}</dd>
                  </div>
                </dl>
              </div>
            );
          }}
        </Resource>
      </Card>

      <Card title="Reliability">
        <Resource state={dlq}>
          {(d) => {
            const stuck = d.depth > 0;
            return (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hs-space-3)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--hs-space-2)' }}>
                  <StatusPill
                    tone={stuck ? 'error' : 'ok'}
                    label={stuck ? `${d.depth} item${d.depth === 1 ? '' : 's'} stuck` : 'All clear'}
                  />
                  <span style={{ color: 'var(--hs-text-secondary)', fontSize: 'var(--hs-text-sm)' }}>
                    {stuck
                      ? 'Some events could not be processed and are parked for review.'
                      : 'No events are stuck. Nothing needs your attention.'}
                  </span>
                </div>
                {stuck && d.parkedSubscribers.length > 0 ? (
                  <dl class="kv">
                    {d.parkedSubscribers.map((s) => (
                      <div class="kvRow" key={s.subscriberId}>
                        <dt>{s.subscriberId}</dt>
                        <dd>{s.reason ?? 'parked'}</dd>
                      </div>
                    ))}
                  </dl>
                ) : null}
              </div>
            );
          }}
        </Resource>
      </Card>
    </Page>
  );
}
