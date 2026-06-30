/*
 * ThemeToggle — System / Light / Dark, as a compact segmented control (FE-2, D-FE-1).
 * Built on native radio inputs so keyboard arrow-navigation + screen-reader semantics come for
 * free. Selected state is conveyed by fill + icon shape + text label (not color alone). Targets
 * are >= 24px (WCAG 2.2 SC 2.5.8). Lives in the AppShell footer.
 */
import type { VNode } from 'preact';
import { useTheme, type ThemePref } from '../lib/theme';
import styles from './ThemeToggle.module.css';

function IconSystem(): VNode {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <rect x="2" y="3" width="12" height="8" rx="1.2" fill="none" stroke="currentColor" stroke-width="1.3" />
      <path d="M6 13.5h4M8 11v2.5" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
    </svg>
  );
}
function IconLight(): VNode {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="3" fill="none" stroke="currentColor" stroke-width="1.3" />
      <path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M12.6 3.4l-1 1M4.4 11.6l-1 1"
        stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
    </svg>
  );
}
function IconDark(): VNode {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M13 9.5A5.5 5.5 0 016.5 3a5.5 5.5 0 100 11 5.5 5.5 0 006.5-4.5z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" />
    </svg>
  );
}

const OPTIONS: { value: ThemePref; label: string; Icon: () => VNode }[] = [
  { value: 'system', label: 'System', Icon: IconSystem },
  { value: 'light', label: 'Light', Icon: IconLight },
  { value: 'dark', label: 'Dark', Icon: IconDark },
];

export function ThemeToggle(): VNode {
  const [pref, setPref] = useTheme();
  return (
    <fieldset class={styles.group}>
      <legend class="sr-only">Color theme</legend>
      {OPTIONS.map((o) => {
        const active = pref === o.value;
        return (
          <label key={o.value} class={`${styles.opt} ${active ? styles.active : ''}`} title={`${o.label} theme`}>
            <input
              class="sr-only"
              type="radio"
              name="hs-theme"
              value={o.value}
              aria-label={`${o.label} theme`}
              checked={active}
              onChange={() => setPref(o.value)}
            />
            <o.Icon />
            <span class={styles.label}>{o.label}</span>
          </label>
        );
      })}
    </fieldset>
  );
}
