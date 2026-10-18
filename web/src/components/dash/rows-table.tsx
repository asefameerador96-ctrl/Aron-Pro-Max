"use client";
// The table body of a dashboard table. Over `LIMIT` rows only the first `LIMIT` are in the DOM; "Show more" adds the next
// block, and the whole table is expanded just before printing so a printout is never short of rows. The CSV always carries every row.
import { useEffect, useState } from "react";
import type { Col, TRow } from "@/components/dash/data-table";
import { Button } from "@/components/kit/button";

export const ROW_LIMIT = 200;

export function RowsTable({ id, columns, rows, moreMsg, limit = ROW_LIMIT }: { id: string; columns: Col[]; rows: TRow[]; moreMsg: string; limit?: number }) {
  const [shown, setShown] = useState(limit);
  useEffect(() => {
    const all = () => setShown(Number.MAX_SAFE_INTEGER);
    window.addEventListener("beforeprint", all);
    return () => window.removeEventListener("beforeprint", all);
  }, []);
  const visible = rows.slice(0, shown);
  const left = rows.length - visible.length;
  return (
    <>
      <table className="stack-sm min-w-full text-body" data-testid={`rows-${id}`} data-rows={rows.length}>
        <thead>
          <tr className="border-b border-divider text-start text-label text-secondary">
            {columns.map((c, i) => (
              <th key={i} scope="col" className={`whitespace-nowrap px-3 py-2 font-semibold ${c.numeric ? "text-end" : "text-start"}`}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-divider">
          {visible.map((r) => (
            <tr key={r.key}>
              {r.cells.map((c, i) => (
                <td key={i} data-label={columns[i]?.header} className={`px-3 py-2 ${columns[i]?.numeric ? "text-end tabular-nums" : "text-start"} ${i === 0 ? "text-body-strong" : ""}`}>
                  {i === 0 && r.href ? (
                    <a href={r.href} className="text-accent underline-offset-2 hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
                      {c}
                    </a>
                  ) : (
                    c
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {left > 0 ? (
        <div className="no-print p-2">
          <Button variant="secondary" testId={`more-${id}`} onClick={() => setShown((s) => s + limit)}>
            {moreMsg.replace("{n}", String(Math.min(limit, left)))}
          </Button>
        </div>
      ) : null}
    </>
  );
}
