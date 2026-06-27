import { render } from 'preact';
import './styles/global.css';
import { App } from './app';

const root = document.getElementById('app');
if (root) render(<App />, root);
