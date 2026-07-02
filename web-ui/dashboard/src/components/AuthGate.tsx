/*
 * AuthGate — first-run token entry (AB-1 / Doc 13 §12).
 * The operator pastes the pairing token Core wrote to config/initial_api_token.
 * Held in memory only (no localStorage/cookies). No pre-auth enumeration.
 */
import { useState } from 'preact/hooks';
import { setToken, getAuthError } from '../lib/auth';
import { BRAND, t } from '../lib/i18n';
import styles from './AuthGate.module.css';

export function AuthGate() {
  const [value, setValue] = useState('');
  const rejected = getAuthError();

  const connect = (e: Event) => {
    e.preventDefault();
    if (value.trim()) setToken(value.trim());
  };

  return (
    <div class={styles.screen}>
      <form class={styles.card} onSubmit={connect}>
        <div class={styles.brand}>{BRAND.productName}</div>
        <h1 class={styles.title}>Connect to your home</h1>
        <p class={styles.lede}>
          {t('auth.tokenHelp')}{' '}
          <code>config/initial_api_token</code>, shown once when the device first started.
        </p>

        {rejected ? (
          <p class={styles.error} role="alert">
            {rejected}
          </p>
        ) : null}

        <label class={styles.label} for="token">
          Pairing token
        </label>
        {/* A pairing token is NOT a password: ask password managers not to capture or
            autofill it (observed live: Bitwarden offered to fill the gate — FE-1).
            The vendor data-attributes are best-effort hints; harmless where unsupported. */}
        <input
          id="token"
          class={styles.input}
          type="password"
          autocomplete="off"
          data-bwignore
          data-1p-ignore
          data-lpignore="true"
          spellcheck={false}
          placeholder="paste token"
          value={value}
          onInput={(e) => setValue((e.target as HTMLInputElement).value)}
        />
        <button class={styles.button} type="submit" disabled={!value.trim()}>
          Connect
        </button>

        <p class={styles.note}>
          This dashboard runs entirely on your local network. Your token stays in this browser tab and is never stored
          or sent anywhere else.
        </p>
      </form>
    </div>
  );
}
