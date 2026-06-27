/* Layout primitives: page scaffold, cards, section headers. Calm, restrained. */
import type { ComponentChildren } from 'preact';
import type { ResponseMeta } from '../lib/api/contract';
import { Freshness } from './feedback';
import styles from './layout.module.css';

export function Page({
  title,
  lede,
  actions,
  meta,
  children,
}: {
  title: string;
  lede?: string;
  actions?: ComponentChildren;
  meta?: ResponseMeta;
  children: ComponentChildren;
}) {
  return (
    <section class={styles.page}>
      <header class={styles.header}>
        <div>
          <h1 class={styles.title}>{title}</h1>
          {lede ? <p class={styles.lede}>{lede}</p> : null}
        </div>
        <div class={styles.headerRight}>
          {meta ? <Freshness meta={meta} /> : null}
          {actions}
        </div>
      </header>
      {children}
    </section>
  );
}

export function Card({
  title,
  aside,
  children,
  pad = true,
}: {
  title?: string;
  aside?: ComponentChildren;
  children: ComponentChildren;
  pad?: boolean;
}) {
  return (
    <div class={styles.card}>
      {title || aside ? (
        <div class={styles.cardHead}>
          {title ? <h2 class={styles.cardTitle}>{title}</h2> : <span />}
          {aside}
        </div>
      ) : null}
      <div class={pad ? styles.cardBody : ''}>{children}</div>
    </div>
  );
}

export function Toolbar({ children }: { children: ComponentChildren }) {
  return <div class={styles.toolbar}>{children}</div>;
}
