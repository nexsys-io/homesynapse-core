/*
 * StatusPill — the canonical status atom.
 * Conveys state with tone + ICON SHAPE + text LABEL (never color alone; WCAG 1.4.1).
 * Used for health, availability, command outcome, run status, and origin.
 *
 * v1.1.2 (FE-VERDICT-2): accepts an optional per-verdict `glyph` path override —
 * the five honest failure modes each carry a DISTINCT shape (lib/verdicts.ts
 * MODE_GLYPHS), so the distinction never rides hue alone — and a `provisional`
 * variant (dashed outline) for outcomes that may still settle (§5.9). The
 * provisional signal never rides styling alone: the label text says it too.
 */
import type { Tone } from '../lib/format';
import styles from './StatusPill.module.css';

const GLYPH: Record<Tone, string> = {
  ok: 'M3.5 7.2l2.2 2.3L10.5 4', // check
  warn: 'M7 2.5l5 8.5H2z', // triangle
  error: 'M3.5 3.5l7 7M10.5 3.5l-7 7', // x
  info: 'M7 6.2v4.3M7 3.7v.05', // i
  unknown: 'M5 5.2a2 2 0 113 1.7c-.6.4-1 .8-1 1.6M7 10.4v.05', // ?
  neutral: 'M7 7m-2 0a2 2 0 104 0a2 2 0 10-4 0', // dot
};

export function StatusPill({
  tone,
  label,
  title,
  size = 'md',
  glyph,
  provisional = false,
}: {
  tone: Tone;
  label: string;
  title?: string;
  size?: 'sm' | 'md';
  /** SVG path override (14×14) — a per-verdict distinct shape. */
  glyph?: string;
  /** Outcome may still settle (§5.9): dashed outline, calm — never a settled pill. */
  provisional?: boolean;
}) {
  const d = glyph ?? GLYPH[tone];
  const filled = !glyph && (tone === 'warn' || tone === 'neutral');
  return (
    <span
      class={`${styles.pill} ${styles[tone]} ${size === 'sm' ? styles.sm : ''} ${provisional ? styles.provisional : ''}`}
      title={title}
    >
      <svg class={styles.glyph} viewBox="0 0 14 14" aria-hidden="true" focusable="false">
        <path d={d} fill={filled ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span>{label}</span>
    </span>
  );
}
