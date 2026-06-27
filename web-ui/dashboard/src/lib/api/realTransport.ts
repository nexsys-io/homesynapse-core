/*
 * HomeSynapse — Real transport (fetch over the loopback HTTP surface).
 * Adds the bearer token to every request (AB-1). Parses problem+json bodies so
 * the client can surface typed errors. No retries here — the poll loop and the
 * per-view error states own retry policy.
 */
import type { RawRequest, RawResponse, Transport } from './client';

export function createRealTransport(baseUrl: string, getToken: () => string | null): Transport {
  return {
    async send(req: RawRequest): Promise<RawResponse> {
      const url = new URL(req.path, baseUrl);
      if (req.query) {
        for (const [k, v] of Object.entries(req.query)) {
          if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
        }
      }
      const headers: Record<string, string> = { Accept: 'application/json' };
      const token = getToken();
      if (token) headers['Authorization'] = `Bearer ${token}`;
      if (req.ifNoneMatch) headers['If-None-Match'] = req.ifNoneMatch;

      const resp = await fetch(url.toString(), { method: req.method, headers });
      const respHeaders: Record<string, string> = {};
      resp.headers.forEach((value, key) => {
        respHeaders[key.toLowerCase()] = value;
      });

      let body: unknown = null;
      if (resp.status !== 304) {
        const text = await resp.text();
        if (text) {
          try {
            body = JSON.parse(text);
          } catch {
            body = text; // non-JSON error body
          }
        }
      }
      return { status: resp.status, headers: respHeaders, body };
    },
  };
}
