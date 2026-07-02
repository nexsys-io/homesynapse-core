/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Force the API transport: 'true' = mock, 'false' = real. Default: mock in dev. */
  readonly VITE_USE_MOCKS?: string;
  /** Dev-runtime contract validation of live responses: 'true' = validate + console.error on drift (FE-1). */
  readonly VITE_VALIDATE?: string;
  /** Dev-server proxy target for /api + /internal (the local Core origin). Default http://127.0.0.1:7070. */
  readonly VITE_CORE_ORIGIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
