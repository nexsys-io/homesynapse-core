/* Resource — render an ApiState uniformly: loading / error / catching-up / ok.
   Keeps every view's data states consistent and never a silent blank. */
import type { ComponentChildren } from 'preact';
import type { ApiState } from '../lib/poll';
import type { ResponseMeta } from '../lib/api/contract';
import { ErrorState, Loading, OfflineState, ReplayingBanner } from './feedback';
import { t } from '../lib/i18n';

/** HERO-1c correction D3: the hero views pass their §7 rows (`explain.loading` / `explain.error.*`)
 *  here; every other view takes the primitives' app defaults (`ui.loading` / `ui.error.*`). */
export interface ResourceLabels {
  loading?: string;
  errorTitle?: string;
  errorBody?: string;
}

export function Resource<T>({
  state,
  labels,
  children,
}: {
  state: ApiState<T>;
  labels?: ResourceLabels;
  children: (data: T, meta?: ResponseMeta) => ComponentChildren;
}) {
  switch (state.status) {
    case 'loading':
      return <Loading label={labels?.loading} />;
    case 'replaying':
      return <ReplayingBanner />;
    case 'auth':
      // FE-114 D4: the auth-state label is the app's `ui.signingIn` row, byte-identical (the widened lint reached it).
      return <Loading label={t('ui.signingIn')} />;
    case 'error':
      return <ErrorState error={state.error} onRetry={state.reload} title={labels?.errorTitle} body={labels?.errorBody} />;
    case 'offline':
      return <OfflineState onRetry={state.reload} />;
    case 'ok':
      return <>{children(state.data as T, state.meta)}</>;
  }
}
