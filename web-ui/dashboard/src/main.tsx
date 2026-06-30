import { render } from 'preact';
import './styles/global.css';
import { App } from './app';
import { initTheme } from './lib/theme';
import { BRAND } from './lib/i18n';

// Reconcile theme state at startup (the pre-paint attribute is set by the inline boot script in
// index.html; this keeps "System" tracking live OS changes and syncs color-scheme + meta color).
initTheme();

// Name-light: the tab title comes from the brand token (rename-survivable). The static <title>
// in index.html is the no-JS fallback.
document.title = BRAND.productName;

const root = document.getElementById('app');
if (root) render(<App />, root);
