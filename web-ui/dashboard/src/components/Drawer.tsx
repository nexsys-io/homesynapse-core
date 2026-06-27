/* Detail drawer — a right-side panel for entity/run detail. Accessible: ESC to
   close, labelled dialog, restores focus, click-outside to dismiss. */
import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import styles from './Drawer.module.css';

export function Drawer({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ComponentChildren;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<Element | null>(null);

  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    panelRef.current?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      (restoreRef.current as HTMLElement | null)?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div class={styles.overlay} onClick={onClose}>
      <div
        ref={panelRef}
        class={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
      >
        <div class={styles.head}>
          <h2 class={styles.title}>{title}</h2>
          <button class={styles.close} onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div class={styles.body}>{children}</div>
      </div>
    </div>
  );
}
