/*
 * HERO-1c C5 (2026-09-13) — the four state cards render the §7 rows through t() (the HERO-1b
 * audit's D7): `explain.loading` · `explain.error.title/.body/.retry` · `explain.offline.title/
 * .body` (+ `.retry`) · `explain.replaying.title/.body`. RED at HEAD: feedback.tsx:13 defaults the
 * spinner to "Loading…"; :33 titles the error card "Something went wrong" (or the wire's problem
 * title) with no keyed body; :65–:67 carry the offline literals; :54 reads `boot.startingBody`,
 * which carries the product name — a Register-C miss on a hero surface. Preservation (green at
 * HEAD, named): both retry buttons already say "Try again"; role="alert" on the error card and
 * role="status" (polite) on the offline/replaying cards; an explicit Loading label passes through.
 * The wire's own problem title and detail stay visible beneath the keyed body (hub words are data).
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { Loading, ErrorState, OfflineState, ReplayingBanner } from './feedback';
import { ApiProblem } from '../lib/api';
import { BRAND, t } from '../lib/i18n';

afterEach(cleanup);
const text = (c: Element) => c.textContent ?? '';

/* HERO-1c correction D3 (2026-09-13, the intake's ruling): the state primitives default to the APP's
 * generic pair (`ui.loading` / `ui.error.*` — app copy, not SPEC §7), and the hero views pass the
 * `explain.*` rows through Resource's `labels`. FLIPPED here (old → new): Loading's default
 * explain.loading → ui.loading; ErrorState's defaults explain.error.* → ui.error.*. RED at the
 * HERO-1c tree: the `ui.*` keys do not exist and the defaults are the hero rows. */
describe('HERO-1c C5 — the state cards are the catalog', () => {
  it('Loading defaults to the app row ui.loading (D3: FLIPPED from explain.loading); the hero row passes through as a label', () => {
    expect(text(render(<Loading />).container)).toContain(t('ui.loading'));
    expect(text(render(<Loading />).container)).not.toContain(t('explain.loading'));
    cleanup();
    expect(text(render(<Loading label={t('explain.loading')} />).container)).toContain(t('explain.loading'));
    cleanup();
    expect(text(render(<Loading label="Signing in…" />).container)).toContain('Signing in…');
  });

  it('ErrorState defaults to the app rows ui.error.title/.body/.retry (D3: FLIPPED from explain.error.*); role="alert"', () => {
    const { container, getByRole } = render(<ErrorState onRetry={() => {}} />);
    expect(text(container)).toContain(t('ui.error.title'));
    expect(text(container)).toContain(t('ui.error.body'));
    expect(text(container)).not.toContain(t('explain.error.title'));
    expect(text(container)).not.toContain('Something went wrong');
    expect(getByRole('alert')).toBeTruthy();
    expect(getByRole('button').textContent).toBe(t('ui.error.retry'));
  });

  it('ErrorState with the hero rows passed as title/body renders them (the hero override) [GREEN at the HERO-1c tree by construction — they were the defaults]', () => {
    const { container, getByRole } = render(<ErrorState title={t('explain.error.title')} body={t('explain.error.body')} onRetry={() => {}} />);
    expect(text(container)).toContain(t('explain.error.title'));
    expect(text(container)).toContain(t('explain.error.body'));
    expect(getByRole('button').textContent).toBe('Try again');
  });

  it("ErrorState with a wire problem keeps the hub's own title and detail visible beneath the keyed body", () => {
    const problem = new ApiProblem({ type: 'https://homesynapse.local/problems/internal-error', title: 'Internal error', status: 500, detail: 'projection read failed' });
    const { container } = render(<ErrorState error={problem} />);
    expect(text(container)).toContain(t('ui.error.title')); // D3: FLIPPED from explain.error.title
    expect(text(container)).toContain('Internal error');
    expect(text(container)).toContain('projection read failed');
    expect(container.querySelector('button')).toBeNull(); // no onRetry, no button
  });

  it('OfflineState: the keyed title and body, the retry, role="status" polite', () => {
    const { container, getByRole } = render(<OfflineState onRetry={() => {}} />);
    expect(text(container)).toContain(t('explain.offline.title'));
    expect(text(container)).toContain(t('explain.offline.body'));
    expect(text(container)).not.toContain('reach your home right now');
    expect(getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(getByRole('button').textContent).toBe(t('explain.error.retry'));
  });

  it('ReplayingBanner: the keyed title and body — and no product name (Register C)', () => {
    const { container, getByRole } = render(<ReplayingBanner />);
    expect(text(container)).toContain(t('explain.replaying.title'));
    expect(text(container)).toContain(t('explain.replaying.body'));
    expect(text(container)).not.toContain(BRAND.productName);
    expect(text(container)).not.toContain('Starting up.');
    expect(getByRole('status').getAttribute('aria-live')).toBe('polite');
  });
});
