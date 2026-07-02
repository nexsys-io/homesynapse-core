/*
 * HomeSynapse — Typed API client.
 * ---------------------------------------------------------------------------
 * Transport-agnostic. The same typed endpoint layer (endpoints.ts) runs over a
 * REAL transport (fetch) or the MOCK transport (mock/) — the swap is ONE switch
 * in index.ts. Cross-cutting contract concerns live here: bearer auth, RFC 9457
 * problem+json, ETag/If-None-Match (304), and first-class detection of the
 * 503 `state-store-replaying` boot state.
 */
import type { Envelope, PaginationMeta, ProblemDetail, ResponseMeta } from './contract';
import { validateAgainstContract, ContractError, type EndpointId } from './shapes';

/* FE-1 dev-runtime validation (FE1_GO_LIVE §A): validate LIVE response bodies against the
 * frozen-contract validators so Core drift is caught at the moment of integration, in the
 * console — the smoke bar is "no console contract errors". Build-time flag: statically
 * replaced by Vite, so production builds without it tree-shake the validators away.
 * A live validator failure is a CROSS-LANE EVENT (report to the hub) — never a client patch. */
const VALIDATE_LIVE = import.meta.env.VITE_VALIDATE === 'true';

export interface RawRequest {
  method: 'GET';
  path: string;
  query?: Record<string, string | number | undefined>;
  ifNoneMatch?: string;
}
export interface RawResponse {
  status: number;
  headers: Record<string, string>;
  body: unknown;
}
export interface Transport {
  send(req: RawRequest): Promise<RawResponse>;
}

export interface ApiResult<T> {
  data: T;
  pagination?: PaginationMeta;
  meta: ResponseMeta;
  etag?: string;
}

/** A typed non-2xx. `replaying` flags the 503 boot/catch-up state for the UI. */
export class ApiProblem extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;
  constructor(problem: ProblemDetail) {
    super(problem.title || problem.type || `HTTP ${problem.status}`);
    this.name = 'ApiProblem';
    this.status = problem.status;
    this.problem = problem;
  }
  get type() {
    return this.problem.type;
  }
  /** 401: prompt for a token. */
  get isAuthRequired() {
    return this.status === 401 || this.problem.type === 'authentication-required';
  }
  /** 403: token invalid/expired. */
  get isForbidden() {
    return this.status === 403 || this.problem.type === 'forbidden';
  }
  /** 503 state-store-replaying: "starting up / catching up", NOT a hard error. */
  get isReplaying() {
    return this.problem.type === 'state-store-replaying';
  }
  /** status 0 / network-unreachable: the hub could not be reached (offline). */
  get isOffline() {
    return this.status === 0 || this.problem.type === 'network-unreachable';
  }
}

function buildPath(path: string, query?: RawRequest['query']): string {
  if (!query) return path;
  const qs = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
  return qs ? `${path}?${qs}` : path;
}

export interface ClientOptions {
  /** Transport carries the bearer token (Doc 13 §12: in-memory session only). */
  transport: Transport;
  /** Called whenever a 401/403 is seen, so the shell can drop to the auth gate. */
  onAuthError?: (p: ApiProblem) => void;
  /** Enable If-None-Match/304 revalidation (default true). */
  useEtags?: boolean;
}

export interface ApiClient {
  get<T>(endpoint: EndpointId, path: string, query?: RawRequest['query']): Promise<ApiResult<T>>;
}

export function createClient(opts: ClientOptions): ApiClient {
  const useEtags = opts.useEtags ?? true;
  const etagCache = new Map<string, { etag: string; result: ApiResult<unknown> }>();

  return {
    async get<T>(endpoint: EndpointId, path: string, query?: RawRequest['query']): Promise<ApiResult<T>> {
      const fullPath = buildPath(path, query);
      const cached = useEtags ? etagCache.get(fullPath) : undefined;
      let res: RawResponse;
      try {
        res = await opts.transport.send({ method: 'GET', path, query, ifNoneMatch: cached?.etag });
      } catch (e) {
        // A transport-level throw = the hub is unreachable (fetch throws TypeError; the mock's
        // 'offline' condition throws too). Surface it as a typed, first-class offline problem.
        if (e instanceof ApiProblem) throw e;
        throw new ApiProblem({
          type: 'network-unreachable',
          title: 'Cannot reach your home',
          status: 0,
          detail: 'The dashboard could not reach the hub. It may be restarting, or the network dropped.',
        });
      }

      if (res.status === 304 && cached) {
        return cached.result as ApiResult<T>;
      }

      if (res.status < 200 || res.status >= 300) {
        const problem = toProblem(res);
        const err = new ApiProblem(problem);
        if ((err.isAuthRequired || err.isForbidden) && opts.onAuthError) opts.onAuthError(err);
        throw err;
      }

      const env = res.body as Envelope<T>;
      if (!env || typeof env !== 'object' || !('data' in env) || !('meta' in env)) {
        throw new ApiProblem({
          type: 'internal-error',
          title: 'Malformed response envelope',
          status: 502,
          detail: `Expected { data, meta } from ${fullPath}`,
        });
      }

      if (VALIDATE_LIVE) {
        // Log-and-continue: drift must be VISIBLE (the FE-1 smoke bar is a clean console),
        // but the view stays renderable so the rest of the pass remains evaluable.
        try {
          validateAgainstContract(endpoint, res.body);
        } catch (e) {
          if (e instanceof ContractError) {
            console.error(
              `[contract-drift] ${endpoint} ${fullPath}: ${e.message} — ` +
                'frozen v1.1 violation on a LIVE response. Cross-lane event: report to the hub; do NOT patch the client.',
            );
          } else {
            throw e;
          }
        }
      }
      const result: ApiResult<T> = {
        data: env.data,
        meta: env.meta,
        ...(env.pagination ? { pagination: env.pagination } : {}),
        ...(res.headers['etag'] ? { etag: res.headers['etag'] } : {}),
      };
      if (useEtags && result.etag) etagCache.set(fullPath, { etag: result.etag, result });
      return result;
    },
  };
}

function toProblem(res: RawResponse): ProblemDetail {
  const b = res.body;
  if (b && typeof b === 'object' && 'status' in b && 'title' in b) {
    return b as ProblemDetail;
  }
  return {
    type: 'internal-error',
    title: typeof b === 'string' && b ? b : `HTTP ${res.status}`,
    status: res.status,
  };
}
