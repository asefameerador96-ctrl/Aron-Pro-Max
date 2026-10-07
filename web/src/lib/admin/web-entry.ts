// Web Entry grid rules (F-WEB-050): integer quantities in the SKU's base unit, sale = issue - return (read-only), calls <= targets.
import type { WebEntryLine } from "./types";

export interface EntryRow {
  sku_id: number;
  issue: string;
  ret: string;
  memos: string;
  /** Sale split by web-entry class (cfg.web.entry_classes), sub-channel id to typed quantity. */
  cls?: Record<string, string>;
}
export type EntryError = { row: number | null; field: "issue" | "ret" | "memos" | "calls" | "classes"; code: "invalid" | "return_exceeds_issue" | "too_big" | "class_sum" };

const INT = /^\d{1,8}$/;
export const MAX_QTY = 10_000_000;
export const MAX_MEMOS = 100_000;

export const saleOf = (r: EntryRow): number | null => {
  if (!INT.test(r.issue || "0") || !INT.test(r.ret || "0")) return null;
  const s = Number(r.issue || "0") - Number(r.ret || "0");
  return s < 0 ? null : s;
};

/** Validate rows and the successful-calls figure; returns the lines to send (rows with any quantity) or the errors. */
export function buildEntry(rows: readonly EntryRow[], calls: string, targetOutlets: number, classes: readonly number[] = []): { lines: WebEntryLine[]; errors: EntryError[] } {
  const errors: EntryError[] = [];
  const lines: WebEntryLine[] = [];
  rows.forEach((r, i) => {
    const issue = r.issue === "" ? "0" : r.issue;
    const ret = r.ret === "" ? "0" : r.ret;
    const memos = r.memos === "" ? "0" : r.memos;
    let bad = false;
    const check = (field: "issue" | "ret" | "memos", value: string, max: number) => {
      if (INT.test(value) && Number(value) <= max) return;
      errors.push({ row: i, field, code: INT.test(value) ? "too_big" : "invalid" });
      bad = true;
    };
    check("issue", issue, MAX_QTY);
    check("ret", ret, MAX_QTY);
    check("memos", memos, MAX_MEMOS);
    if (!bad && Number(ret) > Number(issue)) errors.push({ row: i, field: "ret", code: "return_exceeds_issue" });
    if (!bad && (Number(issue) > 0 || Number(ret) > 0 || Number(memos) > 0)) {
      const line: WebEntryLine = { sku_id: r.sku_id, issue_qty_base: Number(issue), return_qty_base: Number(ret), memo_count: Number(memos) };
      const sale = Number(issue) - Number(ret);
      if (classes.length === 1) {
        line.class_qty_base = { [String(classes[0])]: sale }; // one class: the whole sale belongs to it
      } else if (classes.length > 1) {
        const typed = classes.map((c) => [String(c), (r.cls?.[String(c)] ?? "") === "" ? "0" : r.cls![String(c)]!] as const);
        if (typed.some(([, v]) => !INT.test(v))) errors.push({ row: i, field: "classes", code: "invalid" });
        else {
          const sum = typed.reduce((a, [, v]) => a + Number(v), 0);
          if (sum > 0 && sum !== sale) errors.push({ row: i, field: "classes", code: "class_sum" });
          if (sum > 0) line.class_qty_base = Object.fromEntries(typed.map(([k, v]) => [k, Number(v)]));
        }
      }
      lines.push(line);
    }
  });
  const c = calls === "" ? "0" : calls;
  if (!/^\d{1,6}$/.test(c)) errors.push({ row: null, field: "calls", code: "invalid" });
  else if (Number(c) > targetOutlets) errors.push({ row: null, field: "calls", code: "too_big" });
  return { lines, errors };
}
