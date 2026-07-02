/*
 * DevicesView — A1 list -> A2/A3 detail drawer. LIVE against the real A-class
 * endpoints. Shows availability, freshness, and the typed attribute values.
 * Display names: the v1.1 contract carries an OPTIONAL entity `name` (additive C8) —
 * prefer it when Core sends it; fall back to the humanized entityId (displayName).
 */
import { useState } from 'preact/hooks';
import { api } from '../lib/api';
import type { EntitySummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { availabilityMeta, attrValue, clockTime, displayName, labelFor, timeAgo } from '../lib/format';
import { t } from '../lib/i18n';
import { Page, Card } from '../components/layout';
import { DataTable } from '../components/DataTable';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import { Drawer } from '../components/Drawer';
import { Loading, ErrorState, EmptyState } from '../components/feedback';

export function DevicesView() {
  const state = useApi(() => api.listEntities({ sort: 'ASC' }));
  const [selected, setSelected] = useState<{ entityId: string; name?: string } | null>(null);

  return (
    <Page title="Devices" lede={t('devices.lede')} meta={state.meta}>
      <Card pad={false}>
        <Resource state={state}>
          {(rows: EntitySummary[]) => (
            <DataTable<EntitySummary>
              rows={rows}
              rowKey={(r) => r.entityId}
              onActivate={(r) => setSelected({ entityId: r.entityId, name: r.name })}
              emptyLabel="No devices paired yet."
              columns={[
                { key: 'name', header: 'Device', render: (r) => <strong>{displayName(r)}</strong> },
                {
                  key: 'status',
                  header: 'Status',
                  render: (r) => {
                    const a = availabilityMeta(r.availability);
                    return <StatusPill tone={a.tone} label={a.label} />;
                  },
                },
                {
                  key: 'fresh',
                  header: 'Reading',
                  render: (r) =>
                    r.stale ? (
                      <StatusPill tone="warn" label="Stale" title="This reading may be out of date." size="sm" />
                    ) : (
                      <span style={{ color: 'var(--hs-text-muted)' }}>Current</span>
                    ),
                },
              ]}
            />
          )}
        </Resource>
      </Card>

      <Drawer open={selected !== null} title={selected ? displayName(selected) : ''} onClose={() => setSelected(null)}>
        {selected ? <EntityDetail id={selected.entityId} /> : null}
      </Drawer>
    </Page>
  );
}

function EntityDetail({ id }: { id: string }) {
  const state = useApi(() => api.getEntityState(id));
  if (state.status === 'loading') return <Loading />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={state.reload} />;
  if (state.status !== 'ok' || !state.data) return <EmptyState title="No detail available." />;

  const s = state.data;
  const a = availabilityMeta(s.availability);
  const attrs = Object.entries(s.attributes);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hs-space-4)' }}>
      <div style={{ display: 'flex', gap: 'var(--hs-space-2)', alignItems: 'center' }}>
        <StatusPill tone={a.tone} label={a.label} />
        {s.stale ? <StatusPill tone="warn" label="Stale reading" size="sm" /> : null}
      </div>

      <dl class="kv">
        {attrs.length === 0 ? (
          <p style={{ color: 'var(--hs-text-muted)', fontSize: 'var(--hs-text-sm)' }}>No attributes reported.</p>
        ) : (
          attrs.map(([k, tv]) => (
            <div key={k} class="kvRow">
              <dt>{labelFor(k)}</dt>
              <dd>{attrValue(tv)}</dd>
            </div>
          ))
        )}
        <div class="kvRow">
          <dt>Last changed</dt>
          <dd>{timeAgo(s.lastChanged)}</dd>
        </div>
        <div class="kvRow">
          <dt>Last reported</dt>
          <dd>{clockTime(s.lastReported)}</dd>
        </div>
      </dl>
    </div>
  );
}
