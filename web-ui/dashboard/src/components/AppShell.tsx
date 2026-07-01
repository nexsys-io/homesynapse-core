/* AppShell — sidebar nav + header. The "Ask why" hero entry is featured. */
import type { ComponentChildren } from 'preact';
import { clearToken } from '../lib/auth';
import { usePollCursor, type Phase } from '../lib/poll';
import { href } from '../lib/router';
import type { RouteName } from '../lib/router';
import { ThemeToggle } from './ThemeToggle';
import { BRAND } from '../lib/i18n';
import styles from './AppShell.module.css';

const NAV: { to: string; label: string; match: RouteName[]; icon: string; feature?: boolean }[] = [
  { to: '/overview', label: 'Overview', match: ['overview'], icon: 'M3 9l5-5 5 5M4 8v5h8V8' },
  { to: '/explain', label: 'Ask why', match: ['explain', 'explain-runs', 'explain-run', 'explain-why-not'], icon: 'M5 6a3 3 0 113.7 2.9c-.5.1-.7.4-.7.9v.7M8 12.3v.05', feature: true },
  { to: '/devices', label: 'Devices', match: ['devices', 'device'], icon: 'M4 4h8v5H4zM6.5 12h3M8 9v3' },
  { to: '/events', label: 'Activity', match: ['events'], icon: 'M2 8h3l1.5-4 3 9L13 8h1' },
  { to: '/automations', label: 'Automations', match: ['automations'], icon: 'M8 2v3M8 11v3M2 8h3M11 8h3M8 8m-2 0a2 2 0 104 0a2 2 0 10-4 0' },
  { to: '/health', label: 'Health', match: ['health'], icon: 'M2 8h3l1.5 3 2-6 1.5 3H14' },
];

const PHASE_META: Record<Phase, { label: string; cls: string }> = {
  starting: { label: 'Starting…', cls: 'warn' },
  live: { label: 'Live', cls: 'ok' },
  replaying: { label: 'Catching up', cls: 'warn' },
  error: { label: 'Reconnecting', cls: 'error' },
  auth: { label: 'Sign in', cls: 'unknown' },
  offline: { label: 'Offline', cls: 'error' },
};

export function AppShell({ active, children }: { active: RouteName; children: ComponentChildren }) {
  const { phase } = usePollCursor();
  const pm = PHASE_META[phase];
  return (
    <div class={styles.shell}>
      <nav class={styles.sidebar} aria-label="Main">
        <div class={styles.brand}>
          <span class={styles.dot} /> {BRAND.productName}
        </div>
        <ul class={styles.nav}>
          {NAV.map((item) => {
            const isActive = item.match.includes(active);
            return (
              <li key={item.to}>
                <a
                  href={href(item.to)}
                  class={`${styles.link} ${isActive ? styles.active : ''} ${item.feature ? styles.feature : ''}`}
                  aria-current={isActive ? 'page' : undefined}
                >
                  <svg class={styles.icon} viewBox="0 0 16 16" aria-hidden="true">
                    <path d={item.icon} fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  {item.label}
                </a>
              </li>
            );
          })}
        </ul>
        <div class={styles.footer}>
          <ThemeToggle />
          <div class={styles.footerRow}>
            <span class={`${styles.phase} ${styles[pm.cls]}`} title={`System status: ${pm.label}`}>
              <span class={styles.phaseDot} /> {pm.label}
            </span>
            <button class={styles.signout} onClick={() => clearToken()}>
              Disconnect
            </button>
          </div>
        </div>
      </nav>
      <main class={styles.main}>{children}</main>
    </div>
  );
}
