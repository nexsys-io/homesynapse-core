/*
 * RunChainView — the hero "why did this fire?" for a single run (B3 mock).
 * Renders the plain-language causal chain. The whole product thesis lives on this
 * screen: a stranger reads it aloud and is right.
 */
import { api } from '../lib/api';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { CausalChain } from '../components/CausalChain';
import { ErrorBoundary } from '../components/ErrorBoundary';

export function RunChainView({ runId }: { runId: string }) {
  const state = useApi(() => api.getCausalChain(runId));
  return (
    <Page title="Why this happened" lede={undefined} meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/runs')}>← All runs</a>
      </p>
      <Card>
        {/* The error boundary is LOAD-BEARING (2026-07-27 field evidence): an
            uncontained render throw here killed the polling loop and froze the
            app. A fetch failure renders Resource's honest error card; a RENDER
            failure is contained to this card — the poll loop survives both. */}
        <ErrorBoundary resetKey={runId} onRetry={state.reload}>
          <Resource state={state}>{(chain) => <CausalChain chain={chain} />}</Resource>
        </ErrorBoundary>
      </Card>
    </Page>
  );
}
