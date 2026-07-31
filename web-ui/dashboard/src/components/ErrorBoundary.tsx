/*
 * ErrorBoundary — contain a render throw; never let a view take the app down.
 * ---------------------------------------------------------------------------
 * FE-LIVE-V112 item 1, evidence-backed (the 2026-07-27 devtools-chain-glance
 * return): an uncontained render throw in the causal-chain view killed the
 * view's polling loop — the "Updated" stamp froze and the application read as
 * hung, turning a cosmetic defect into an apparent outage. This boundary is
 * therefore LOAD-BEARING, not stylistic (the error-posture law).
 *
 * Honesty rule: this card names WHAT failed — the display, not the request.
 * A fetch failure renders the Resource/ErrorState card ("the request failed,
 * retry"); a render failure renders THIS card. The two are never conflated,
 * and neither is ever a silent blank or an eternal spinner.
 */
import { Component, type ComponentChildren } from 'preact';
import styles from './feedback.module.css';

/** Test-locked copy (the stranger test): plain, calm, honest about the failure. */
export const RENDER_ERROR_TITLE = 'This view hit a problem displaying the record';
export const RENDER_ERROR_BODY =
  'The record itself arrived and is preserved — the display failed while drawing it. The rest of the dashboard keeps updating.';

interface Props {
  /** Called on "Try again" after the boundary resets (e.g. the view's reload). */
  onRetry?: () => void;
  /** When this changes (e.g. a new runId), a tripped boundary resets itself. */
  resetKey?: string;
  children: ComponentChildren;
}
interface State {
  failed: boolean;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  componentDidCatch(error: Error) {
    // Visible, never swallowed: the console carries the real error for
    // diagnosis while the surface stays honest and alive.
    console.error('[render-error] contained by ErrorBoundary:', error);
    this.setState({ failed: true });
  }

  componentDidUpdate(prev: Props) {
    if (this.state.failed && prev.resetKey !== this.props.resetKey) {
      this.setState({ failed: false });
    }
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div class={styles.center} role="alert">
        <p class={styles.errorTitle}>{RENDER_ERROR_TITLE}</p>
        <p class={styles.muted}>{RENDER_ERROR_BODY}</p>
        <button
          class={styles.retry}
          onClick={() => {
            this.setState({ failed: false });
            this.props.onRetry?.();
          }}
        >
          Try again
        </button>
      </div>
    );
  }
}
