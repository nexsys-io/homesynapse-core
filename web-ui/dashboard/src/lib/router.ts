/*
 * HomeSynapse — Hash router (Doc 13 §3.4).
 * Hash-based so the Javalin SPA fallback serves index.html for any /dashboard/*
 * path with zero server route config. Tiny + dependency-free (bundle budget).
 */
import { useEffect, useState } from 'preact/hooks';

export type RouteName =
  | 'overview'
  | 'devices'
  | 'device'
  | 'events'
  | 'health'
  | 'automations'
  | 'explain'
  | 'explain-runs'
  | 'explain-run'
  | 'explain-why-not';

export interface Route {
  name: RouteName;
  params: Record<string, string>;
}

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#\/?/, '').split('?')[0] ?? '';
  const seg = path.split('/').filter(Boolean).map(decodeURIComponent);

  if (seg.length === 0) return { name: 'overview', params: {} };

  switch (seg[0]) {
    case 'devices':
      return seg[1] ? { name: 'device', params: { id: seg[1] } } : { name: 'devices', params: {} };
    case 'events':
      return { name: 'events', params: {} };
    case 'health':
      return { name: 'health', params: {} };
    case 'automations':
      return { name: 'automations', params: {} };
    case 'explain':
      if (seg[1] === 'runs') return { name: 'explain-runs', params: {} };
      if (seg[1] === 'run' && seg[2]) return { name: 'explain-run', params: { runId: seg[2] } };
      if (seg[1] === 'why-not') return { name: 'explain-why-not', params: seg[2] ? { automationId: seg[2] } : {} };
      return { name: 'explain', params: {} };
    case 'overview':
      return { name: 'overview', params: {} };
    default:
      return { name: 'overview', params: {} };
  }
}

export function useHashRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(location.hash));
    window.addEventListener('hashchange', onChange);
    if (!location.hash) location.replace('#/overview');
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

export function navigate(to: string): void {
  const hash = to.startsWith('#') ? to : `#${to.startsWith('/') ? '' : '/'}${to}`;
  if (location.hash !== hash) location.hash = hash;
}

export function href(to: string): string {
  return to.startsWith('#') ? to : `#${to.startsWith('/') ? '' : '/'}${to}`;
}
