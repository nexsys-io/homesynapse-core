/*
 * App root. Auth gate when there is no session token; otherwise the shell with the
 * single coalesced poll loop (PollProvider) and the hash-routed view switch.
 */
import { useEffect, useState } from 'preact/hooks';
import { hasToken, onTokenChange } from './lib/auth';
import { PollProvider } from './lib/poll';
import { useHashRoute, type Route } from './lib/router';
import { AppShell } from './components/AppShell';
import { AuthGate } from './components/AuthGate';
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

  if (!authed) return <AuthGate />;
  return (
    <PollProvider>
      <Shell />
    </PollProvider>
  );
}

function Shell() {
  const route = useHashRoute();
  return <AppShell active={route.name}>{renderView(route)}</AppShell>;
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
