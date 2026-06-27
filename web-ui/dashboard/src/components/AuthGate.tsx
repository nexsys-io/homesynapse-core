/*
 * AuthGate — first-run token entry (AB-1 / Doc 13 §12).
 * The operator pastes the pairing token Core wrote to config/initial_api_token.
 * Held in memory only (no localStorage/cookies). No pre-auth enumeration.
 */
import { useState } from 'preact/hooks';
import { setToken, getAuthError } from '../lib/auth';
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
        <div class={styles.brand}>HomeSynapse</div>
        <h1 class={styles.title}>Connect to your home</h1>
        <p class={styles.lede}>
          Paste the pairing token from your HomeSynapse device. You&rsquo;ll find it in{' '}
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
        <input
          id="token"
          class={styles.input}
          type="password"
          autocomplete="off"
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
