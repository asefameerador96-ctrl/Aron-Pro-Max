import type { ReactNode } from "react";
import { TableScroll } from "./table-scroll";

export interface Column<Row> {
  key: string;
  header: string;
  render: (row: Row) => ReactNode;
  align?: "left" | "right";
}

/** Presentational table on the data surface (web-glass s8.1): sticky glass header, density-driven rows, numeric columns
 *  right-aligned with tabular digits, hover bg-pressed. Server rendered; sorting and paging belong to the list operation. */
export function DataTable<Row>({ columns, rows, rowKey, empty, caption }: { columns: readonly Column<Row>[]; rows: readonly Row[]; rowKey: (row: Row) => string; empty: string; caption: string }) {
  if (rows.length === 0) {
    return (
      <p data-testid="table-empty" className="glass-card border-dashed p-6 text-center text-body text-secondary">
        {empty}
      </p>
    );
  }
  return (
    // The scroll region is keyboard reachable (axe scrollable-region-focusable) and named after the caption; it stamps data-stuck
    // while rows scroll under the sticky header (TableScroll).
    <TableScroll className="glass-data max-h-[calc(100dvh-var(--topbar-h)-8rem)] min-h-0 text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" tabIndex={0} role="region" aria-label={caption}>
      <table className="min-w-full text-table" data-testid="data-table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={`whitespace-nowrap px-3 text-label text-secondary ${c.align === "right" ? "text-end" : "text-start"}`}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)}>
              {columns.map((c) => (
                <td key={c.key} className={`max-w-[32rem] px-3 py-2 align-top break-words ${c.align === "right" ? "text-end tabular-nums" : "text-start"}`}>
                  {c.render(r)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </TableScroll>
  );
}
