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
import { t } from '../lib/i18n';

function projectionMeta(mode: ProjectionMode | string | null | undefined): { tone: Tone; label: string; plain: string } {
  switch (mode) {
    case 'LIVE':
      return { tone: 'ok', label: 'Live', plain: t('health.live') };
    case 'TRANSITION':
      return { tone: 'warn', label: 'Catching up', plain: 'Almost there — finishing catch-up after a restart.' };
    case 'REPLAY':
      return { tone: 'warn', label: 'Starting up', plain: 'Rebuilding current state from the activity log.' };
  }
  // Open-vocabulary hardening (NEW-3 sweep, the closed-switch class): an
  // off-vocabulary mode string previously returned undefined and `.tone`
  // crashed the HEALTH surface — the one place that must stay honest when
  // something is odd. Render what was recorded, in the honest register.
  return {
    tone: 'unknown',
    label: mode == null || mode === '' ? 'State not recorded' : `Recorded as "${mode}"`,
    plain: 'The hub reported a state this dashboard does not recognize yet. Nothing here is hidden — this is what it said.',
  };
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
                    {/* v1.1.1: parkedSubscribers is the ratified id list (strings);
                        the additive subscribers[] detail fills the per-id count when present. */}
                    {d.parkedSubscribers.map((id) => {
                      const detail = d.subscribers?.find((s) => s.subscriberId === id);
                      return (
                        <div class="kvRow" key={id}>
                          <dt>{id}</dt>
                          <dd>
                            {detail
                              ? `${detail.dlqDepth} item${detail.dlqDepth === 1 ? '' : 's'} parked`
                              : 'parked'}
                          </dd>
                        </div>
                      );
                    })}
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
