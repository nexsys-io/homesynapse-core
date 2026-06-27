/* Resource — render an ApiState uniformly: loading / error / catching-up / ok.
   Keeps every view's data states consistent and never a silent blank. */
import type { ComponentChildren } from 'preact';
import type { ApiState } from '../lib/poll';
import type { ResponseMeta } from '../lib/api/contract';
import { ErrorState, Loading, ReplayingBanner } from './feedback';

export function Resource<T>({
  state,
  children,
}: {
  state: ApiState<T>;
  children: (data: T, meta?: ResponseMeta) => ComponentChildren;
}) {
  switch (state.status) {
    case 'loading':
      return <Loading />;
    case 'replaying':
      return <ReplayingBanner />;
    case 'auth':
      return <Loading label="Signing in…" />;
    case 'error':
      return <ErrorState error={state.error} onRetry={state.reload} />;
    case 'ok':
      return <>{children(state.data as T, state.meta)}</>;
  }
}
