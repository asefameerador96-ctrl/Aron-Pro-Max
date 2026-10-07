import { NextResponse, type NextRequest } from "next/server";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { AUDIT_CSV_HEADER, AUDIT_FILTER_KEYS, auditCsvRow, cleanAuditFilter, fetchAuditForExport } from "@/lib/admin/audit";
import { toCsv } from "@/lib/admin/csv";

/** GET /api/bff/admin-export/audit?<filter>: the audit rows of a filter as CSV (at most 10,000 rows). */
export async function GET(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const raw: Record<string, string | undefined> = {};
  for (const k of AUDIT_FILTER_KEYS) raw[k] = req.nextUrl.searchParams.get(k) ?? undefined;
  const r = await fetchAuditForExport(auth.session.at, cleanAuditFilter(raw));
  if (!r.ok) return auth.finish(problemResponse(r.status, r.problem.code));
  const body = toCsv(AUDIT_CSV_HEADER, r.data.rows.map(auditCsvRow));
  return auth.finish(new NextResponse(body, { status: 200, headers: { "Content-Type": "text/csv; charset=utf-8", "Content-Disposition": 'attachment; filename="audit.csv"', "Cache-Control": "no-store", "X-Export-Truncated": r.data.truncated ? "1" : "0" } }));
}
