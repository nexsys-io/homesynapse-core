/*
 * RunsView — the "why did this fire?" entry list (B3 mock). Recent automation
 * runs; click one to see its causal chain. Run outcome is shown honestly here too
 * (a COMPLETED run can still carry "sent, not confirmed" — surfaced on the chain).
 */
import { api } from '../lib/api';
import type { RunSummary } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { navigate } from '../lib/router';
import { runStatusMeta, timeAgo } from '../lib/format';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { DataTable } from '../components/DataTable';
import { StatusPill } from '../components/StatusPill';

export function RunsView() {
  const state = useApi(() => api.listRuns({ limit: 50 }));
  return (
    <Page title="Why did something happen?" lede="Pick a run to see exactly why it fired, step by step." meta={state.meta}>
      <Card pad={false}>
        <Resource state={state}>
          {(rows: RunSummary[]) => (
            <DataTable<RunSummary>
              rows={rows}
              rowKey={(r) => r.runId}
              onActivate={(r) => navigate(`/explain/run/${r.runId}`)}
              emptyLabel="No automation runs yet."
              columns={[
                { key: 'name', header: 'Automation', render: (r) => <strong>{r.automationName}</strong> },
                { key: 'when', header: 'When', render: (r) => timeAgo(r.triggeredAt) },
                {
                  key: 'status',
                  header: 'Outcome',
                  render: (r) => {
                    const m = runStatusMeta(r.status);
                    return <StatusPill tone={m.tone} label={m.label} title={r.terminalReason ?? undefined} size="sm" />;
                  },
                },
              ]}
            />
          )}
        </Resource>
      </Card>
    </Page>
  );
}
