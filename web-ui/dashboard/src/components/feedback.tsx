/*
 * Feedback primitives: loading, error, empty, the calm "catching up" boot state,
 * and the freshness indicator. Every data-fetching surface uses these so states
 * are consistent and never a silent blank.
 */
import type { ComponentChildren } from 'preact';
import type { ResponseMeta } from '../lib/api/contract';
import { ApiProblem } from '../lib/api';
import { timeAgo } from '../lib/format';
import { t } from '../lib/i18n';
import styles from './feedback.module.css';

/* HERO-1c C5 (SPEC §7 `explain.offline.*` · `explain.replaying.*`; the HERO-1b audit's D7) and its
 * D3 correction (the intake's ruling): the four state cards are catalog rows behind t(). Resource
 * mounts these primitives on EVERY view, so Loading and ErrorState DEFAULT to the app's generic
 * pair (`ui.loading` / `ui.error.*`) and take the hero's rows (`explain.loading` /
 * `explain.error.*`) as props — the hero views pass them through Resource's `labels`. The
 * offline and replaying copy is generic and stays the §7 rows. */
export function Loading({ label = t('ui.loading') }: { label?: string }) {
  return (
    <div class={styles.center} role="status" aria-live="polite">
      <span class={styles.spinner} aria-hidden="true" />
      <span class={styles.muted}>{label}</span>
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div class={styles.center}>
      <p class={styles.emptyTitle}>{title}</p>
      {hint ? <p class={styles.muted}>{hint}</p> : null}
    </div>
  );
}

export function ErrorState({
  error,
  onRetry,
  title = t('ui.error.title'),
  body = t('ui.error.body'),
  retry = t('ui.error.retry'),
}: {
  error?: Error;
  onRetry?: () => void;
  /** The hero passes its §7 rows (`explain.error.title` / `.body` / `.retry`); the app pair is the default. */
  title?: string;
  body?: string;
  retry?: string;
}) {
  // The hub's own words (a problem's title and detail, or an Error's message) stay visible
  // beneath the keyed body — wire data, never hidden; the sentences around them are the catalog's.
  const isProblem = error instanceof ApiProblem;
  const said = isProblem ? [error.problem.title, error.problem.detail].filter(Boolean).join(' — ') : error?.message;
  return (
    <div class={styles.center} role="alert">
      <p class={styles.errorTitle}>{title}</p>
      <p class={styles.muted}>{body}</p>
      {said ? <p class={styles.muted}>{said}</p> : null}
      {onRetry ? (
        <button class={styles.retry} onClick={onRetry}>
          {retry}
        </button>
      ) : null}
    </div>
  );
}

/** Calm, first-class boot/catch-up state (Doc 13 §0) — NOT an error toast. */
export function ReplayingBanner() {
  return (
    <div class={styles.replaying} role="status" aria-live="polite">
      <span class={styles.spinner} aria-hidden="true" />
      <span>
        <strong>{t('explain.replaying.title')}</strong> {t('explain.replaying.body')}
      </span>
    </div>
  );
}

/** Honest offline/degraded state — the hub is unreachable. Calm (polite live region), not an
    alarm; the poll loop keeps retrying with backoff, and retry is offered. Never a fake success. */
export function OfflineState({ onRetry }: { onRetry?: () => void }) {
  return (
    <div class={styles.center} role="status" aria-live="polite">
      <p class={styles.emptyTitle}>{t('explain.offline.title')}</p>
      <p class={styles.muted}>{t('explain.offline.body')}</p>
      {onRetry ? (
        <button class={styles.retry} onClick={onRetry}>
          {t('explain.error.retry')}
        </button>
      ) : null}
    </div>
  );
}

/** Freshness signal sourced from the response meta (viewPosition cursor + time). */
export function Freshness({ meta }: { meta?: ResponseMeta }) {
  if (!meta) return null;
  return (
    <span class={styles.freshness} title={`Projection cursor ${meta.viewPosition}`}>
      Updated {timeAgo(meta.timestamp)}
    </span>
  );
}

export function CenteredPanel({ children }: { children: ComponentChildren }) {
  return <div class={styles.panel}>{children}</div>;
}
