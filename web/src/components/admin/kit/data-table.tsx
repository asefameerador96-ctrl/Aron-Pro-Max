import type { ReactNode } from "react";

export interface Column<Row> {
  key: string;
  header: string;
  render: (row: Row) => ReactNode;
  align?: "left" | "right";
}

/** Presentational table: header, rows, empty state. Server rendered; sorting and paging belong to the list operation. */
export function DataTable<Row>({ columns, rows, rowKey, empty, caption }: { columns: readonly Column<Row>[]; rows: readonly Row[]; rowKey: (row: Row) => string; empty: string; caption: string }) {
  if (rows.length === 0) {
    return (
      <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
        {empty}
      </p>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="data-table">
        <caption className="sr-only">{caption}</caption>
        <thead className="bg-slate-50">
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={`px-3 py-2 font-semibold text-slate-700 ${c.align === "right" ? "text-right" : "text-left"}`}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((r) => (
            <tr key={rowKey(r)} className="hover:bg-slate-50">
              {columns.map((c) => (
                <td key={c.key} className={`max-w-[32rem] break-words px-3 py-2 align-top ${c.align === "right" ? "text-right" : "text-left"}`}>
                  {c.render(r)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
