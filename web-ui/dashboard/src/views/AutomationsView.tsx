/*
 * AutomationsView — the component-based automation list (B3 supporting surface).
 * Each automation links into both hero halves.
 */
import { api } from '../lib/api';
import type { AutomationSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { DataTable } from '../components/DataTable';
import { StatusPill } from '../components/StatusPill';

export function AutomationsView() {
  const state = useApi(() => api.listAutomations());
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
                        {r.components.map((c) => c.summary).join(' · ')}
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
