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
import { useRefResolver } from '../lib/registry';
import { t } from '../lib/i18n';

export function RunChainView({ runId }: { runId: string }) {
  const state = useApi(() => api.getCausalChain(runId));
  // FE-HONEST-1 (§10-J): the registry census backs the loud unresolvable-ref
  // rendering. Until it is in (or if it is incomplete), nothing is accused.
  const resolveRef = useRefResolver();
  return (
    <Page title={t('explain.run.title')} lede={undefined} meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/runs')}>← All runs</a>
      </p>
      <Card>
        {/* The error boundary is LOAD-BEARING (2026-07-27 field evidence): an
            uncontained render throw here killed the polling loop and froze the
            app. A fetch failure renders Resource's honest error card; a RENDER
            failure is contained to this card — the poll loop survives both. */}
        <ErrorBoundary resetKey={runId} onRetry={state.reload}>
          {/* HERO-1c correction D3: the hero's own loading / error rows (SPEC §7) ride Resource's labels. */}
          <Resource state={state} labels={{ loading: t('explain.loading'), errorTitle: t('explain.error.title'), errorBody: t('explain.error.body') }}>
            {(chain) => <CausalChain chain={chain} resolveRef={resolveRef} />}
          </Resource>
        </ErrorBoundary>
      </Card>
    </Page>
  );
}
