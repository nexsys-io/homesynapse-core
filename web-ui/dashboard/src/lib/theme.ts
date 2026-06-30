/*
 * Theme preference (FE-2, D-FE-1). DARK is the recorded brand default. The operator may
 * choose Light, Dark, or System (follow the OS); the choice persists across reloads and the
 * "System" choice tracks OS changes live. Same token names in every theme (see tokens.css),
 * so nothing below touches component styles — it only sets [data-theme] on <html>.
 *
 * Storage note: unlike the API session token — which is in-memory ONLY because it is a secret
 * (AB-1) — the theme preference is non-sensitive UI state, so persisting it in localStorage is
 * appropriate. No network and no third party are involved; local-first (INV-LF-01) holds.
 *
 * First paint is handled by a tiny inline boot script in index.html (it sets [data-theme]
 * before CSS applies, so there is no light-flash). This module keeps things in sync at runtime.
 */
import { useEffect, useState } from 'preact/hooks';

export type ThemePref = 'system' | 'light' | 'dark';
export type EffectiveTheme = 'light' | 'dark';

const KEY = 'hs-theme';
/* Bootstrap mirror of --hs-bg (neutral-50 / neutral-950) for the address-bar/meta color, which
   must be set before CSS variables resolve. Keep in sync with tokens.dtcg.json bg values. */
const BG: Record<EffectiveTheme, string> = { light: '#f6f8fa', dark: '#0d1014' };

const listeners = new Set<() => void>();
let mediaBound = false;

function readPref(): ThemePref {
  try {
    const v = localStorage.getItem(KEY);
    if (v === 'light' || v === 'dark' || v === 'system') return v;
  } catch {
    /* storage unavailable — fall through to the default */
  }
  return 'system';
}

function systemPrefersDark(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches;
}

export function getThemePref(): ThemePref {
  return readPref();
}

export function effectiveTheme(pref: ThemePref = readPref()): EffectiveTheme {
  if (pref === 'system') return systemPrefersDark() ? 'dark' : 'light';
  return pref;
}

/** Apply a preference to <html>: set/clear [data-theme], sync color-scheme + the meta color. */
export function applyTheme(pref: ThemePref): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  if (pref === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', pref);

  const eff = effectiveTheme(pref);
  root.style.colorScheme = eff; // native form controls + scrollbars match the theme
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', BG[eff]);
}

export function setThemePref(pref: ThemePref): void {
  try {
    localStorage.setItem(KEY, pref);
  } catch {
    /* non-fatal: still apply for this session */
  }
  applyTheme(pref);
  listeners.forEach((l) => l());
}

/** Call once at startup: reconcile runtime state and keep "System" live to OS changes. */
export function initTheme(): void {
  applyTheme(readPref());
  if (!mediaBound && typeof matchMedia !== 'undefined') {
    mediaBound = true;
    const mql = matchMedia('(prefers-color-scheme: dark)');
    mql.addEventListener('change', () => {
      if (readPref() === 'system') {
        applyTheme('system');
        listeners.forEach((l) => l());
      }
    });
  }
}

/** Preact hook: [current preference, setter, the resolved effective theme]. */
export function useTheme(): [ThemePref, (p: ThemePref) => void, EffectiveTheme] {
  const [pref, setPref] = useState<ThemePref>(readPref());
  useEffect(() => {
    const l = () => setPref(readPref());
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  }, []);
  return [pref, setThemePref, effectiveTheme(pref)];
}
