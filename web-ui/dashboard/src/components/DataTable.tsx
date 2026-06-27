/* DataTable — a small, accessible table. Rows can be activated by click/Enter. */
import type { ComponentChildren } from 'preact';
import styles from './DataTable.module.css';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ComponentChildren;
  width?: string;
  align?: 'start' | 'end';
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onActivate,
  emptyLabel = 'Nothing here yet.',
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onActivate?: (row: T) => void;
  emptyLabel?: string;
}) {
  if (rows.length === 0) {
    return <p class={styles.empty}>{emptyLabel}</p>;
  }
  return (
    <div class={styles.scroll}>
      <table class={styles.table}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} style={{ width: c.width, textAlign: c.align === 'end' ? 'right' : 'left' }}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              class={onActivate ? styles.clickable : ''}
              tabIndex={onActivate ? 0 : undefined}
              onClick={onActivate ? () => onActivate(row) : undefined}
              onKeyDown={
                onActivate
                  ? (e: KeyboardEvent) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onActivate(row);
                      }
                    }
                  : undefined
              }
            >
              {columns.map((c) => (
                <td key={c.key} style={{ textAlign: c.align === 'end' ? 'right' : 'left' }}>
                  {c.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
