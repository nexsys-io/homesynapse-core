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

export function RunChainView({ runId }: { runId: string }) {
  const state = useApi(() => api.getCausalChain(runId));
  return (
    <Page title="Why this happened" lede={undefined} meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/runs')}>← All runs</a>
      </p>
      <Card>
        <Resource state={state}>{(chain) => <CausalChain chain={chain} />}</Resource>
      </Card>
    </Page>
  );
}
