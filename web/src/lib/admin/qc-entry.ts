import type { CodeItem } from "./types";

export interface QcCell {
  sku_id: number;
  fault_type_code: string;
  qty_base: number;
}
export const QC_MAX_QTY = 10_000_000;

/** Non-zero cells of the SKU x fault grid; errors for entries that are not whole numbers within range. */
export function buildQcRows(cells: Record<string, string>): { rows: QcCell[]; errors: string[] } {
  const rows: QcCell[] = [];
  const errors: string[] = [];
  for (const [key, raw] of Object.entries(cells)) {
    if (raw === "") continue;
    const [sku, code] = key.split("|") as [string, string];
    if (!/^\d{1,8}$/.test(raw) || Number(raw) > QC_MAX_QTY) {
      errors.push(key);
      continue;
    }
    if (Number(raw) > 0) rows.push({ sku_id: Number(sku), fault_type_code: code, qty_base: Number(raw) });
  }
  return { rows, errors };
}

/** Fault types in use: not retired (valid_to is exclusive: a code retired today is gone today), ordered by `sort`, MFC group before MKT. */
export function activeFaults(items: readonly CodeItem[], today: string): CodeItem[] {
  return items
    .filter((i) => (i.valid_to === null || i.valid_to === undefined || i.valid_to > today) && (i.valid_from ?? "0000-00-00") <= today)
    .sort((a, b) => String(a.attrs?.group ?? "").localeCompare(String(b.attrs?.group ?? "")) || a.sort - b.sort);
}
