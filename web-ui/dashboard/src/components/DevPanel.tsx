/*
 * DevPanel — the mock scenario/demo control (T1.2). Dev + demo only (rendered by app.tsx only
 * when API_MODE === 'mock'; trivially absent in a real-backend build). Puts every data scenario
 * and every transport condition one click away — the happy-path demo, the honest "sent, not
 * confirmed" path, each "why didn't it fire?" verdict, 503/offline/slow/401/403/ETag — and mirrors
 * the choice into the URL (?scenario=…&mock=…) so a demo state is shareable and deep-linkable.
 */
import { useEffect, useState } from 'preact/hooks';
import { SCENARIOS } from '../lib/api/mock/scenarios';
import {
  TRANSPORT_CONDITIONS,
  getCondition,
  getScenarioId,
  setCondition,
  setScenario,
  subscribeMock,
  type TransportCondition,
} from '../lib/api/mock/mockState';
import { kickPoll } from '../lib/poll';
import styles from './DevPanel.module.css';

export function DevPanel() {
  const [open, setOpen] = useState(false);
  const [, force] = useState(0);
  useEffect(() => subscribeMock(() => force((n) => n + 1)), []);

  const scenario = getScenarioId();
  const condition = getCondition();
  const pickScenario = (id: string) => {
    setScenario(id);
    kickPoll();
  };
  const pickCondition = (c: TransportCondition) => {
    setCondition(c);
    kickPoll();
  };

  return (
    <div class={styles.root}>
      {open ? (
        <div class={styles.panel} role="dialog" aria-label="Demo scenarios">
          <div class={styles.header}>
            <span class={styles.title}>Demo scenarios</span>
            <button class={styles.close} onClick={() => setOpen(false)} aria-label="Close demo panel">
              <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
            </button>
          </div>

          <div class={styles.legend} id="dp-data">Data</div>
          <div class={styles.grid} role="radiogroup" aria-labelledby="dp-data">
            {SCENARIOS.map((s) => (
              <button
                key={s.id}
                role="radio"
                aria-checked={scenario === s.id}
                class={`${styles.chip} ${scenario === s.id ? styles.active : ''}`}
                title={s.blurb}
                onClick={() => pickScenario(s.id)}
              >
                {s.label}
              </button>
            ))}
          </div>

          <div class={styles.legend} id="dp-transport">Transport</div>
          <div class={styles.grid} role="radiogroup" aria-labelledby="dp-transport">
            {TRANSPORT_CONDITIONS.map((c) => (
              <button
                key={c.id}
                role="radio"
                aria-checked={condition === c.id}
                class={`${styles.chip} ${condition === c.id ? styles.active : ''}`}
                title={c.blurb}
                onClick={() => pickCondition(c.id)}
              >
                {c.label}
              </button>
            ))}
          </div>

          <p class={styles.note}>Mock only. Shareable via the page URL.</p>
        </div>
      ) : null}

      <button
        class={styles.fab}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-label="Demo scenarios"
      >
        <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
          <path
            d="M6.2 2.5h3.6M7 2.5v3.4l-2.7 5.2a1.2 1.2 0 0 0 1.07 1.75h5.26a1.2 1.2 0 0 0 1.07-1.75L9 5.9V2.5M5.6 10h4.8"
            fill="none"
            stroke="currentColor"
            stroke-width="1.3"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
        </svg>
        <span class={styles.fabLabel}>Demo</span>
      </button>
    </div>
  );
}
