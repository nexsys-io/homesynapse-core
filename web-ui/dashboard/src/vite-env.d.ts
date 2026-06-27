/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Force the API transport: 'true' = mock, 'false' = real. Default: mock in dev. */
  readonly VITE_USE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
