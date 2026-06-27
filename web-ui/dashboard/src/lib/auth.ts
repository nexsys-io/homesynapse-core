/*
 * HomeSynapse — Session auth (in-memory only).
 * ---------------------------------------------------------------------------
 * AB-1 / Doc 13 §12: the operator pastes the first-run pairing token
 * (Core writes it to config/initial_api_token). We hold it in a module variable
 * for the session — NEVER localStorage, cookies, or sessionStorage (no credential
 * persistence; closing the tab clears it). No pre-auth enumeration; no stored
 * secret beyond this in-memory token.
 */

let sessionToken: string | null = null;
let authError: string | null = null;
const listeners = new Set<(token: string | null) => void>();

/** A human message for the auth gate when a token is rejected (set by the client). */
export function setAuthError(msg: string | null): void {
  authError = msg;
}
export function getAuthError(): string | null {
  return authError;
}

export function getToken(): string | null {
  return sessionToken;
}

export function setToken(token: string | null): void {
  sessionToken = token && token.trim() ? token.trim() : null;
  if (sessionToken) authError = null; // a fresh attempt clears the prior rejection
  for (const l of listeners) l(sessionToken);
}

export function clearToken(): void {
  setToken(null);
}

export function hasToken(): boolean {
  return sessionToken !== null;
}

/** Subscribe to token changes (the shell flips between auth gate and app). */
export function onTokenChange(fn: (token: string | null) => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
