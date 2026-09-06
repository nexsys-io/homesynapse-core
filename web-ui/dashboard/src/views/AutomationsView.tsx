/*
 * AutomationsView — the component-based automation list (B3 supporting surface).
 * Each automation links into both hero halves.
 * v1.1.3 (FE-113 / CG-1): each component may carry `ref` — the ONE entity it
 * addresses by identity — rendered through the registry census (FE-HONEST-1
 * §10-J): resolved → the registry name; dangling on a complete census → LOUD;
 * null / absent → the summary alone, no claim.
 */
import { api } from '../lib/api';
import type { AutomationSummary, ComponentSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { refLabel, UNRESOLVED_REF_HELP, UNRESOLVED_REF_PHRASE, UNRESOLVED_REF_PILL } from '../lib/format';
import { useRefResolver, type RefResolver } from '../lib/registry';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { DataTable } from '../components/DataTable';
import { StatusPill } from '../components/StatusPill';

/** One component's summary, plus its entity when the wire named one (PRESENT-object
 *  `ref`). A null or absent ref renders the summary alone — the wire made no
 *  single-entity claim, so neither does the surface. Same LOUD render as the chain. */
function ComponentLine({ c, resolveRef }: { c: ComponentSummary; resolveRef: RefResolver }) {
  if (!c.ref) return <span>{c.summary}</span>;
  const res = resolveRef(c.ref.id);
  if (res.kind === 'dangling') {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--hs-space-2)', flexWrap: 'wrap' }}>
        <span>
          {c.summary} — <span style={{ fontFamily: 'var(--hs-font-mono, monospace)' }}>entity {c.ref.id}</span> — {UNRESOLVED_REF_PHRASE}
        </span>
        <StatusPill tone="error" label={UNRESOLVED_REF_PILL} title={UNRESOLVED_REF_HELP} size="sm" />
      </span>
    );
  }
  // Resolved (or unverified): the registry's name for the entity, unless the summary
  // already says it — the same name twice on one line is noise, not evidence. The
  // census still ran; only the dangling case needs to be LOUD.
  const label = refLabel(c.ref.id, res);
  if (c.summary.toLowerCase().includes(label.toLowerCase())) return <span>{c.summary}</span>;
  return (
    <span>
      {c.summary} <span style={{ color: 'var(--hs-text-muted)' }}>({label})</span>
    </span>
  );
}

export function AutomationsView() {
  const state = useApi(() => api.listAutomations());
  // v1.1.3: the registry census for component refs; 'unverified' until it is in.
  const resolveRef = useRefResolver();
  return (
    <Page title="Automations" lede="The rules running your home." meta={state.meta}>
      <Card pad={false}>
        <Resource state={state}>
          {(rows: AutomationSummary[]) => (
            <DataTable<AutomationSummary>
              rows={rows}
              rowKey={(r) => r.automationId}
              emptyLabel="No automations yet."
              columns={[
                {
                  key: 'name',
                  header: 'Automation',
                  render: (r) => (
                    <div>
                      <strong>{r.name}</strong>
                      <div style={{ fontSize: 'var(--hs-text-xs)', color: 'var(--hs-text-muted)' }}>
                        {r.components.map((c, i) => (
                          <span key={i}>
                            {i > 0 ? ' · ' : ''}
                            <ComponentLine c={c} resolveRef={resolveRef} />
                          </span>
                        ))}
                      </div>
                    </div>
                  ),
                },
                {
                  key: 'state',
                  header: 'State',
                  render: (r) =>
                    r.enabled ? <StatusPill tone="ok" label="On" size="sm" /> : <StatusPill tone="unknown" label="Off" size="sm" />,
                },
                {
                  key: 'explain',
                  header: 'Explain',
                  render: (r) => (
                    <div style={{ display: 'flex', gap: 'var(--hs-space-3)', fontSize: 'var(--hs-text-sm)', whiteSpace: 'nowrap' }}>
                      {r.lastRunId ? <a href={href(`/explain/run/${r.lastRunId}`)}>Why did it?</a> : null}
                      <a href={href(`/explain/why-not/${r.automationId}`)}>Why didn&rsquo;t it?</a>
                    </div>
                  ),
                },
              ]}
            />
          )}
        </Resource>
      </Card>
    </Page>
  );
}
