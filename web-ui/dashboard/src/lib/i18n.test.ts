/*
 * The brand-token pin (FE-SWAP-GATE). One positive assertion on the VALUE of
 * BRAND.productName so the rename is red-first by construction: every other
 * name-touching assertion in the suite is token-relative (it imports BRAND and
 * follows the flip), so without this row a rename — partial, accidental, or
 * real — passes the whole suite silently. This row is the gate.
 */
import { describe, it, expect } from 'vitest';
import { BRAND } from './i18n';

describe('brand token', () => {
  it('pins the working product name', () => {
    // The rename gate: this row goes RED at the swap and is updated in the same commit as i18n.ts:18 — W-11.
    expect(BRAND.productName).toBe('HomeSynapse');
  });
});
