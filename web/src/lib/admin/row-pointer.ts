// Row errors of a refused batch: the API answers 400 ERR_VALIDATION with `errors[].pointer` "body.rows/<0-based index>/<member>"
// (putRouteTargets, importOutlets). The sheet shows them like the preview's errors, so the pointer becomes {row (1-based), column, code}.
export interface PointerIssue {
  row: number;
  column: string;
  code: string;
}

const ROW_POINTER = /^(?:body\.|\/)?rows\/(\d{1,5})(?:\/([A-Za-z_]+))?$/;

/** Row errors found in a problem's `errors` member; anything that is not a row pointer is ignored. `columnOf` maps a body member to the sheet column. */
export function rowIssuesOf(errors: unknown, columnOf: (member: string) => string = (m) => m): PointerIssue[] {
  if (!Array.isArray(errors)) return [];
  const out: PointerIssue[] = [];
  for (const e of errors) {
    const p = (e as { pointer?: unknown } | null)?.pointer;
    const code = (e as { code?: unknown } | null)?.code;
    if (typeof p !== "string" || typeof code !== "string") continue;
    const m = ROW_POINTER.exec(p);
    if (m) out.push({ row: Number(m[1]) + 1, column: columnOf(m[2] ?? "sheet"), code });
  }
  return out;
}

/** Body member of a route-target row to the column of the sheet and the report. */
export const TARGET_MEMBER_COLUMN: Record<string, string> = { route_code: "route", sku_code: "sku", month: "month", qty_base: "quantity" };
