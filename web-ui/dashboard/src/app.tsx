/*
 * App root. Auth gate when there is no session token; otherwise the shell with the
 * single coalesced poll loop (PollProvider) and the hash-routed view switch.
 */
import { useEffect, useState } from 'preact/hooks';
import { hasToken, onTokenChange } from './lib/auth';
import { PollProvider } from './lib/poll';
import { useHashRoute, type Route } from './lib/router';
import { API_MODE } from './lib/api';
import { AppShell } from './components/AppShell';
import { AuthGate } from './components/AuthGate';
import { DevPanel } from './components/DevPanel';
import { ErrorBoundary } from './components/ErrorBoundary';
import { OverviewView } from './views/OverviewView';
import { DevicesView } from './views/DevicesView';
import { HealthView } from './views/HealthView';
import { EventsView } from './views/EventsView';
import { AutomationsView } from './views/AutomationsView';
import { ExplainHubView } from './views/ExplainHubView';
import { RunsView } from './views/RunsView';
import { RunChainView } from './views/RunChainView';
import { WhyNotView } from './views/WhyNotView';

export function App() {
  const [authed, setAuthed] = useState(hasToken());
  useEffect(() => onTokenChange((t) => setAuthed(t !== null)), []);

  return (
    <>
      {authed ? (
        <PollProvider>
          <Shell />
        </PollProvider>
      ) : (
        <AuthGate />
      )}
      {/* Dev/demo scenario switcher — mock builds only; absent in a real-backend build. */}
      {API_MODE === 'mock' ? <DevPanel /> : null}
    </>
  );
}

function Shell() {
  const route = useHashRoute();
  return (
    <AppShell active={route.name}>
      {/* THE ERROR-POSTURE LAW, ENFORCED AT THE CLASS (NEW-2; G1 rehearsal §6.3):
          the boundary wraps the WHOLE view switch, inside AppShell, so ANY view's
          render throw degrades to the honest render-failure card while the nav,
          the status footer, and the poll loop (PollProvider, above) stay alive.
          The 2026-07-27 / 2026-08-16 incident class — an uncontained throw
          killing the view and freezing the app behind a stale spinner — is
          unreachable by construction: no view renders outside this boundary.

          resetKey is the full route identity (name + params), so navigating to
          any other view — or another id within a view — resets a tripped
          boundary; a crash never follows the user. "Try again" resets the
          boundary, which remounts the view; useApi refetches on mount, so no
          onRetry wiring is needed at this level. Views may still mount their
          own inner boundary for tighter retry semantics (RunChainView does —
          reload without remount); this outer mount is the floor, not a cap. */}
      <ErrorBoundary resetKey={routeKey(route)}>{renderView(route)}</ErrorBoundary>
    </AppShell>
  );
}

/** Stable identity for a route INSTANCE (name + ordered params). */
function routeKey(route: Route): string {
  const params = Object.keys(route.params)
    .sort()
    .map((k) => `${k}=${route.params[k]}`)
    .join('&');
  return `${route.name}?${params}`;
}

function renderView(route: Route) {
  switch (route.name) {
    case 'overview':
      return <OverviewView />;
    case 'devices':
    case 'device':
      return <DevicesView />;
    case 'events':
      return <EventsView />;
    case 'health':
      return <HealthView />;
    case 'automations':
      return <AutomationsView />;
    case 'explain':
      return <ExplainHubView />;
    case 'explain-runs':
      return <RunsView />;
    case 'explain-run':
      return <RunChainView runId={route.params.runId ?? ''} />;
    case 'explain-why-not':
      return <WhyNotView automationId={route.params.automationId} />;
  }
}
