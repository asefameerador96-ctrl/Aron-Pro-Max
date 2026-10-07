import type { CodeItem, CodeListKey } from "./types";

export const CODE_PATTERN = /^[a-z][a-z0-9_]{1,40}$/;

/** The reason-code tables of F-ADM-060 (QC fault types have their own page, F-ADM-023). */
export const REASON_LISTS: readonly CodeListKey[] = ["force_reason", "edit_reason", "void_reason", "day_exception_reason", "visit_outcome", "feedback_category", "task_type", "skip_reason", "stock_variance_reason", "leave_type", "payment_mode", "outlet_close_reason", "submit_void_reason"];

/** List-specific attributes kept in `attrs` (docs/24: group MFC or MKT and applies_to app or web on qc_fault_type). */
export const ATTR_FIELDS: Partial<Record<CodeListKey, { name: string; options: string[] }[]>> = {
  qc_fault_type: [
    { name: "group", options: ["MFC", "MKT"] },
    { name: "applies_to", options: ["app", "web"] },
  ],
};

export function validateItems(items: readonly CodeItem[], attrs: { name: string; options: string[] }[]): { index: number; field: string; code: string }[] {
  const out: { index: number; field: string; code: string }[] = [];
  const seen = new Set<string>();
  items.forEach((it, i) => {
    if (!CODE_PATTERN.test(it.code)) out.push({ index: i, field: "code", code: "invalid" });
    if (seen.has(it.code)) out.push({ index: i, field: "code", code: "duplicate" });
    seen.add(it.code);
    if (it.label_en.trim().length < 1 || it.label_en.length > 120) out.push({ index: i, field: "label_en", code: "required" });
    if ((it.label_bn ?? "").length > 120) out.push({ index: i, field: "label_bn", code: "too_long" });
    if (!Number.isInteger(it.sort)) out.push({ index: i, field: "sort", code: "invalid" });
    for (const a of attrs) if (!a.options.includes(String(it.attrs?.[a.name] ?? ""))) out.push({ index: i, field: a.name, code: "required" });
  });
  return out;
}
