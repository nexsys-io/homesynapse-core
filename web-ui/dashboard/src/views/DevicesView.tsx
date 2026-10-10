/*
 * DevicesView — A1 list -> A2/A3 detail drawer. LIVE against the real A-class
 * endpoints. Shows the recovery card, freshness, and the typed attribute values.
 * Display names: the v1.1 contract carries an OPTIONAL entity `name` (additive C8) —
 * prefer it when Core sends it; fall back to the humanized entityId (displayName).
 *
 * HERO-U2b R2 (2026-10-09; design/recovery-card-v1/SPEC.md §2 surfaces 1–2, §6 B): the
 * row's Status cell and the detail's head ARE the recovery card — the §3 state line
 * replaces the "Available / Offline / Not determined yet" pill and the
 * availabilityEvidence prose. The card reads the A1 row (the only read carrying the J1
 * keys), so the drawer is keyed by entity id and looks the live row up on every poll.
 * `stale` is a fact about a READING, not the device: the row's "Stale" pill moved off
 * the state and onto the Reading cell beside the stamp it describes; the detail's
 * "Stale reading" pill stays beside the values (:152's home). The card never says it.
 */
import { useState } from 'preact/hooks';
import { api } from '../lib/api';
import type { EntitySummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import {
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
import { RecoveryCard } from '../components/RecoveryCard';
import { Drawer } from '../components/Drawer';
import { Loading, ErrorState, EmptyState } from '../components/feedback';

export function DevicesView() {
  const state = useApi(() => api.listEntities({ sort: 'ASC' }));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // The drawer's row is the LIVE list row (refetched on every viewPosition change), not a click-time snapshot.
  const selected = selectedId !== null && state.status === 'ok' ? state.data?.find((r) => r.entityId === selectedId) ?? null : null;

  return (
    <Page title="Devices" lede={t('devices.lede')} meta={state.meta}>
      <Card pad={false}>
        <Resource state={state}>
          {(rows: EntitySummary[]) => (
            <DataTable<EntitySummary>
              rows={rows}
              rowKey={(r) => r.entityId}
              onActivate={(r) => setSelectedId(r.entityId)}
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
                  // HERO-U2b R2: the recovery card, dense (L1 only — the label with its instant and the line;
                  // the contract sentence and L2 live on the device page). The decision is lib/recovery.ts's
                  // for the row's stage; the flag alone was never a live-contact claim, and the card says what
                  // the system last concluded in the three words (SPEC §1).
                  render: (r) => <RecoveryCard row={r} name={displayName(r)} dense />,
                },
                {
                  key: 'fresh',
                  header: 'Reading',
                  render: (r) => {
                    // SPEC §6 B (HERO-U2b): "Stale" is about the READING — it sits beside the stamp it
                    // describes, and no longer hides the stamp (or stands in for the device's state).
                    const stale = r.stale ? <StatusPill tone="warn" label="Stale" title="This reading may be out of date." size="sm" /> : null;
                    // §10-I (FE-HONEST-1): a list row with NO report time makes no
                    // freshness claim ("Current" with no evidence was a lie by
                    // omission). v1.1.3 (FE-113 / CG-3) splits that into the TRI-STATE
                    // the wire actually serves — three facts, three renders:
                    //   key ABSENT  → a pre-v1.1.3 hub: em-dash + the no-claim title
                    //                 (unchanged behavior);
                    //   PRESENT-null → this hub serves report times and has none on
                    //                 record for this entity: em-dash + ITS OWN title;
                    //   PRESENT-string → the date-qualified stamp (lastReportedCell —
                    //                 the ONE lawful instant parse; never 1970).
                    const stamp = !('lastReported' in r) ? (
                      <span style={{ color: 'var(--hs-text-muted)' }} title={LIST_FRESHNESS_NO_CLAIM_TITLE}>
                        —
                      </span>
                    ) : r.lastReported == null || r.lastReported === '' ? (
                      <span style={{ color: 'var(--hs-text-muted)' }} title={LIST_FRESHNESS_NULL_TITLE}>
                        —
                      </span>
                    ) : (
                      <span>{lastReportedCell(r.lastReported)}</span>
                    );
                    return (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--hs-space-2)', flexWrap: 'wrap' }}>
                        {stamp}
                        {stale}
                      </span>
                    );
                  },
                },
              ]}
            />
          )}
        </Resource>
      </Card>

      <Drawer open={selectedId !== null} title={selected ? displayName(selected) : ''} onClose={() => setSelectedId(null)}>
        {selectedId !== null ? <EntityDetail id={selectedId} row={selected} /> : null}
      </Drawer>
    </Page>
  );
}

function EntityDetail({ id, row }: { id: string; row: EntitySummary | null }) {
  const state = useApi(() => api.getEntityState(id));
  if (state.status === 'loading') return <Loading />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={state.reload} />;
  if (state.status !== 'ok' || !state.data) return <EmptyState title="No detail available." />;

  const s = state.data;
  // Brightness: the % comes from Core's DERIVED `brightness_percent` data key —
  // never a client-side rescale of the canonical 0–254 level. When the derived
  // key is present, the raw level row is folded into it (shown as the detail).
  const bright = brightnessDisplay(s.attributes);
  const attrs = Object.entries(s.attributes).filter(
    ([k]) => !(bright && (k === 'brightness' || k === 'brightness_percent')),
  );
  // HERO-U2b R2 (SPEC §2 surface 2): the card sits under the name, above the attributes, and reads the A1 row
  // (the J1 keys live there). If the list no longer carries the row (it left the registry between polls), the
  // A3 state's four mirror keys render the S1 form — never a blank head.
  const cardRow: EntitySummary = row ?? { entityId: s.entityId, name: s.name, availability: s.availability, stale: s.stale, lastReported: s.lastReported };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hs-space-4)' }}>
      <RecoveryCard row={cardRow} name={displayName(cardRow)} />

      {/* SPEC §6 B: the reading's own freshness word stays with the values it describes. */}
      {s.stale ? (
        <div style={{ display: 'flex', gap: 'var(--hs-space-2)', alignItems: 'center' }}>
          <StatusPill tone="warn" label="Stale reading" size="sm" title="This reading may be out of date." />
        </div>
      ) : null}

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
