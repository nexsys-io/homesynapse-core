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
import {
  availabilityEvidence,
  availabilityMeta,
  attrValue,
  brightnessDisplay,
  displayName,
  labelFor,
  lastReportedCell,
  LIST_FRESHNESS_NO_CLAIM_TITLE,
  LIST_FRESHNESS_NULL_TITLE,
  timeAgo,
} from '../lib/format';
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
                {
                  // §10-H (FE-HONEST-1): these rows are ENTITIES — a device can
                  // expose several. The old 'Device' header put an entity ULID
                  // under a device label, so a row could not be correlated with
                  // a `device_adopted` log line. Label it truthfully and show
                  // the raw entity id, which IS what the log carries.
                  key: 'name',
                  header: 'Entity',
                  render: (r) => (
                    <div>
                      <strong>{displayName(r)}</strong>
                      <div style={{ fontSize: 'var(--hs-text-xs)', color: 'var(--hs-text-muted)', fontFamily: 'var(--hs-font-mono, monospace)' }}>
                        {r.entityId}
                      </div>
                      {/* v1.1.3 (FE-113 / CG-2): the owning device's id — the token that
                          correlates this row with the `device_adopted` log line (§10-H).
                          Rendered ONLY when the wire carried a string: null (this hub has
                          no device on record) and absent (a pre-v1.1.3 hub) both render
                          NOTHING — absence renders absence, never "null", never a
                          placeholder. Same muted mono line as the entity id; no new
                          column, no new landmark. */}
                      {typeof r.deviceId === 'string' && r.deviceId !== '' ? (
                        <div style={{ fontSize: 'var(--hs-text-xs)', color: 'var(--hs-text-muted)', fontFamily: 'var(--hs-font-mono, monospace)' }}>
                          {t('devices.deviceIdLabel')} {r.deviceId}
                        </div>
                      ) : null}
                    </div>
                  ),
                },
                {
                  key: 'status',
                  header: 'Status',
                  render: (r) => {
                    // G2 honesty: the flag is what the system last CONCLUDED —
                    // the title says so (AVAILABLE is never a live-contact claim).
                    const a = availabilityMeta(r.availability);
                    return <StatusPill tone={a.tone} label={a.label} title={a.help} />;
                  },
                },
                {
                  key: 'fresh',
                  header: 'Reading',
                  render: (r) => {
                    if (r.stale) {
                      return <StatusPill tone="warn" label="Stale" title="This reading may be out of date." size="sm" />;
                    }
                    // §10-I (FE-HONEST-1): a list row with NO report time makes no
                    // freshness claim ("Current" with no evidence was a lie by
                    // omission). v1.1.3 (FE-113 / CG-3) splits that into the TRI-STATE
                    // the wire actually serves — three facts, three renders:
                    //   key ABSENT  → a pre-v1.1.3 hub: em-dash + the no-claim title
                    //                 (unchanged behaviour);
                    //   PRESENT-null → this hub serves report times and has none on
                    //                 record for this entity: em-dash + ITS OWN title;
                    //   PRESENT-string → the date-qualified stamp (lastReportedCell —
                    //                 the ONE lawful instant parse; never 1970).
                    if (!('lastReported' in r)) {
                      return (
                        <span style={{ color: 'var(--hs-text-muted)' }} title={LIST_FRESHNESS_NO_CLAIM_TITLE}>
                          —
                        </span>
                      );
                    }
                    if (r.lastReported == null || r.lastReported === '') {
                      return (
                        <span style={{ color: 'var(--hs-text-muted)' }} title={LIST_FRESHNESS_NULL_TITLE}>
                          —
                        </span>
                      );
                    }
                    return <span>{lastReportedCell(r.lastReported)}</span>;
                  },
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
  // Brightness: the % comes from Core's DERIVED `brightness_percent` data key —
  // never a client-side rescale of the canonical 0–254 level. When the derived
  // key is present, the raw level row is folded into it (shown as the detail).
  const bright = brightnessDisplay(s.attributes);
  const attrs = Object.entries(s.attributes).filter(
    ([k]) => !(bright && (k === 'brightness' || k === 'brightness_percent')),
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hs-space-4)' }}>
      <div style={{ display: 'flex', gap: 'var(--hs-space-2)', alignItems: 'center' }}>
        <StatusPill tone={a.tone} label={a.label} title={a.help} />
        {s.stale ? <StatusPill tone="warn" label="Stale reading" size="sm" /> : null}
      </div>

      {/* Availability is EVIDENCE WITH AGE — the flag alone can outlive reality
          (a rehydrated "Available" can persist while the device is off-network
          until the next recheck), and UNKNOWN after a restart is honest, not a
          fault. Always pair the flag with when the device was last heard from. */}
      <p style={{ color: 'var(--hs-text-muted)', fontSize: 'var(--hs-text-sm)', margin: 0 }} role="status">
        {availabilityEvidence(s.availability, s.lastReported)}
      </p>

      <dl class="kv">
        {attrs.length === 0 && !bright ? (
          <p style={{ color: 'var(--hs-text-muted)', fontSize: 'var(--hs-text-sm)' }}>No attributes reported.</p>
        ) : (
          <>
            {bright ? (
              <div class="kvRow">
                <dt>Brightness</dt>
                <dd
                  title={
                    bright.key === 'brightness_percent'
                      ? 'Percentage derived by the hub from the device’s reported level.'
                      : 'The device’s reported level. A percentage is shown once the hub derives it.'
                  }
                >
                  {bright.text}
                </dd>
              </div>
            ) : null}
            {attrs.map(([k, tv]) => (
              <div key={k} class="kvRow">
                <dt>{labelFor(k)}</dt>
                <dd>{attrValue(tv)}</dd>
              </div>
            ))}
          </>
        )}
        <div class="kvRow">
          {/* §10-H: the raw entity id, verbatim — the token that correlates a
              row with the hub's own log lines. Monospace, copyable. */}
          <dt>Entity ID</dt>
          <dd style={{ fontFamily: 'var(--hs-font-mono, monospace)', fontSize: 'var(--hs-text-xs)' }}>{s.entityId}</dd>
        </div>
        <div class="kvRow">
          <dt>Last changed</dt>
          <dd>{timeAgo(s.lastChanged)}</dd>
        </div>
        <div class="kvRow">
          {/* NEW-6: date-qualified (a report can be days old — a bare clock time
              reads as today), and derived from the SAME parse as the prose above,
              so the row and the sentence can never contradict (DX-20). An
              unreadable stamp renders honest absence — never a 1970 misread. */}
          <dt>Last reported</dt>
          {/* §10-G store-truth: three honest states — readable (date-qualified),
              on-record-but-unreadable (the store HOLDS the row; say so — never
              "not recorded"), or no report at all. One parse (DX-20). */}
          <dd>{lastReportedCell(s.lastReported)}</dd>
        </div>
      </dl>
    </div>
  );
}
