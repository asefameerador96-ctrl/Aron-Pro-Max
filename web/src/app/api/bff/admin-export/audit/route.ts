import { NextResponse, type NextRequest } from "next/server";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { AUDIT_CSV_HEADER, AUDIT_EXPORT_MAX_ROWS, AUDIT_FILTER_KEYS, auditCsvRow, cleanAuditFilter, fetchAuditForExport } from "@/lib/admin/audit";
import { toCsv } from "@/lib/admin/csv";

/** GET /api/bff/admin-export/audit?<filter>: the audit rows of a filter as CSV (at most 10,000 rows). */
export async function GET(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const raw: Record<string, string | undefined> = {};
  for (const k of AUDIT_FILTER_KEYS) raw[k] = req.nextUrl.searchParams.get(k)?.slice(0, 64) || undefined; // same truncation as the page
  const r = await fetchAuditForExport(auth.session.at, cleanAuditFilter(raw));
  if (!r.ok) return auth.finish(problemResponse(r.status, r.problem.code));
  const rows: unknown[][] = r.data.rows.map(auditCsvRow);
  if (r.data.truncated) rows.push([`truncated: only the first ${AUDIT_EXPORT_MAX_ROWS} rows are included; narrow the filter`]);
  const body = toCsv(AUDIT_CSV_HEADER, rows);
  return auth.finish(new NextResponse(body, { status: 200, headers: { "Content-Type": "text/csv; charset=utf-8", "Content-Disposition": 'attachment; filename="audit.csv"', "Cache-Control": "no-store", "X-Export-Truncated": r.data.truncated ? "1" : "0" } }));
}
