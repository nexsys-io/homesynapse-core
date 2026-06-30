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

export function Loading({ label = 'Loading…' }: { label?: string }) {
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

export function ErrorState({ error, onRetry }: { error?: Error; onRetry?: () => void }) {
  const isProblem = error instanceof ApiProblem;
  const title = isProblem ? error.problem.title : 'Something went wrong';
  const detail = isProblem ? error.problem.detail : error?.message;
  return (
    <div class={styles.center} role="alert">
      <p class={styles.errorTitle}>{title}</p>
      {detail ? <p class={styles.muted}>{detail}</p> : null}
      {onRetry ? (
        <button class={styles.retry} onClick={onRetry}>
          Try again
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
        <strong>Starting up.</strong> {t('boot.startingBody')}
      </span>
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
