/*
 * NEW-3 corpus sweep — HealthView vs an off-vocabulary wire (RED-FIRST).
 * ---------------------------------------------------------------------------
 * The same closed-switch class as verdictMeta (see format.wire-nullability):
 * HealthView's local projectionMeta() switched over the three frozen
 * ProjectionMode values with no default arm, so an unrecognized mode string on
 * the wire returned undefined and `.tone` crashed the view — on the SYSTEM
 * HEALTH surface, whose whole job is staying honest when things are odd.
 * Post-fix the unknown value renders in the honest register.
 */
import { it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { HealthView } from './HealthView';
import { api } from '../lib/api';
import { RENDER_ERROR_TITLE } from '../components/ErrorBoundary';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const META = { viewPosition: 7, timestamp: '2026-08-17T12:00:00.000Z' };

it('an unrecognized projection mode renders honestly — never a crash on the health surface', async () => {
  vi.spyOn(api, 'getProjection').mockResolvedValue({
    data: { mode: 'MIGRATING', viewPosition: 7, lagEvents: 0, projectionVersion: 5 },
    meta: META,
  } as never);
  vi.spyOn(api, 'getDlq').mockResolvedValue({ data: { depth: 0, parkedSubscribers: [] }, meta: META } as never);

  const { container } = render(<HealthView />);
  await act(async () => {});

  const text = container.textContent ?? '';
  expect(text).toContain('Recorded as "MIGRATING"'); // honest register, as recorded
  expect(text).not.toContain(RENDER_ERROR_TITLE);
  expect(text).toContain('All clear'); // the rest of the surface still renders
});

it('the three frozen modes are untouched (preservation pin)', async () => {
  vi.spyOn(api, 'getProjection').mockResolvedValue({
    data: { mode: 'LIVE', viewPosition: 7, lagEvents: 0, projectionVersion: 5 },
    meta: META,
  } as never);
  vi.spyOn(api, 'getDlq').mockResolvedValue({ data: { depth: 0, parkedSubscribers: [] }, meta: META } as never);

  const { container } = render(<HealthView />);
  await act(async () => {});
  expect(container.textContent ?? '').toContain('Live');
});
