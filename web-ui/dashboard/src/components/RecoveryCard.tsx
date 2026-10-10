/*
 * RecoveryCard — one device's recovery line (HERO-U2b R2; design/recovery-card-v1/SPEC.md §2–§3, §8).
 * ---------------------------------------------------------------------------
 * Three surfaces, one component: the Devices row (`dense` — L1 only), the entity detail, and the hero's
 * why-not card under "Watching" when the joined row is dark. Two disclosure levels and no third: L1 is the
 * state pill (glyph + the §7 label, with its instant) and the sentence beneath (the contract sentence, or the
 * row's own line; R5's second line); L2 is the technical fact one expand away — the absolute instants ("last
 * heard" vs "last report" said), the recorded reason verbatim, the signal at the last frame. The decision is
 * lib/recovery.ts's; every sentence is a §7 row behind t(); this file holds no literal copy.
 *
 * Accessibility (SPEC §8): shape AND label on every state, colour reinforcing (StatusPill's glyph is
 * aria-hidden; the label is text); `recovery.a11y.state` as visually-hidden text per card; ONE polite
 * role="status" region, present and silent, announcing once when the row's state changes on a later read
 * (the rejoin moment — the row flips by its own frame, SPEC §5 (5); this build runs no timer — the hero's
 * pattern). The pill swaps state with no fade under prefers-reduced-motion (the module's rule).
 *
 * NOT in this build: the act ("Open a window for this device" — row 3, Q3 (b)), the countdown, the gesture
 * card, the S3 sentences (row 4). The dark rows render label + line (+ R5's second line) and L2; the act's
 * slot is below the line, marked.
 */
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { EntitySummary } from '../lib/api/contract';
import { recoveryA11y, recoveryRow, stageOf } from '../lib/recovery';
import { t } from '../lib/i18n';
import { StatusPill } from './StatusPill';
import styles from './RecoveryCard.module.css';

export function RecoveryCard({
  row,
  name,
  dense = false,
  now,
}: {
  row: EntitySummary;
  /** The device's display name (format.displayName) — the a11y sentence's subject. */
  name: string;
  /** The device row: L1 only (no contract sentence, no L2). */
  dense?: boolean;
  /** Test seam — the instant "now" is measured from. */
  now?: number;
}) {
  const d = recoveryRow(row, stageOf(row), now);
  const a11y = recoveryA11y(name, d);
  // L2's <summary> says one thing at a time ("Show details" / "Hide details") — the accessible name is the
  // visible word, not both.
  const [open, setOpen] = useState(false);
  // The rejoin (or any state change) on a later read is announced ONCE: compare the row key with the
  // previous render's; the region is always present so assistive tech is subscribed, and silent otherwise.
  const prevKey = useRef<string | null>(null);
  const announcement = prevKey.current !== null && prevKey.current !== d.key ? a11y : '';
  useLayoutEffect(() => {
    prevKey.current = d.key;
  });
  return (
    <div class={`${styles.card} ${dense ? styles.dense : ''}`} data-row={d.key} data-form={d.form} data-stage={d.stage}>
      <span class="sr-only">{a11y}</span>
      <div role="status" aria-live="polite" class="sr-only">{announcement}</div>
      <div class={styles.headline}>
        <StatusPill tone={d.tone} label={d.label} glyph={d.glyph.d} glyphDashed={d.glyph.dashed} wrap />
      </div>
      <p class={styles.line}>
        {d.line}
        {!dense && d.beneath ? <> {d.beneath}</> : null}
      </p>
      {d.line2 ? <p class={styles.line}>{d.line2}</p> : null}
      {/* Row 3's slot (the act): "Open a window for this device" renders here when the row is dark — after Nick's Q3/Q6 word. */}
      {!dense ? (
        <details class={styles.l2} open={open} onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
          <summary class={styles.summary}>{open ? t('recovery.a11y.collapse') : t('recovery.a11y.expand')}</summary>
          <ul class={styles.facts}>
            {d.l2.map((f) => (
              <li key={f.key}>{f.text}</li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}
