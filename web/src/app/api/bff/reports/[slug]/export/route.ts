import { NextResponse, type NextRequest } from "next/server";
import { businessDate } from "@/lib/i18n";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { canSeeReport, reportBySlug } from "@/lib/reports/catalog";
import { buildReportQuery, withFormat } from "@/lib/reports/query";
import { runReportRaw } from "@/lib/reports/server";

// GET /api/bff/reports/<slug>/export?<same filters as the screen>&format=xlsx|print|pdf
// Builds the query with the same function as the page (so the export equals the screen), asks the server for the workbook
// (logged, watermarked, formula-sanitised there: docs/24 s12.3) and streams it. 202 = an export job: back to the page with its id.
export async function GET(req: NextRequest, ctx: { params: Promise<{ slug: string }> }): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const { slug } = await ctx.params;
  const report = reportBySlug(slug);
  if (!report || !canSeeReport(report, auth.session.user.role)) return auth.finish(problemResponse(404, "ERR_NOT_FOUND"));
  const sp: Record<string, string | string[]> = {};
  for (const k of new Set(req.nextUrl.searchParams.keys())) sp[k] = req.nextUrl.searchParams.getAll(k);
  const fmt = req.nextUrl.searchParams.get("format");
  const format = fmt === "print" && report.print ? "print" : fmt === "pdf" && report.pdf ? "pdf" : report.excel === false ? null : "xlsx";
  if (!format) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const q = withFormat(buildReportQuery(report, sp, auth.session.scope), format);
  const r = await runReportRaw(auth.session.at, report.key, q);
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  const type = r.response.headers.get("content-type") ?? "";
  if (r.response.status === 202) {
    const job = (await r.response.json().catch(() => null)) as { export_id?: string } | null;
    // A relative Location: the browser stays on its own host, so a spoofed X-Forwarded-Host cannot steer it.
    const back = new URL(`/reports/${slug}`, "http://self.invalid");
    for (const [k, v] of req.nextUrl.searchParams) if (k !== "format") back.searchParams.append(k, v);
    if (job?.export_id && /^[0-9a-f-]{36}$/.test(job.export_id)) back.searchParams.set("job", job.export_id);
    return auth.finish(new NextResponse(null, { status: 303, headers: { Location: back.pathname + back.search } }));
  }
  const day = businessDate();
  const headers: Record<string, string> = { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };
  if (format === "xlsx") headers["Content-Disposition"] = `attachment; filename="aron-${slug}-${day}.xlsx"`;
  else headers["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox";
  return auth.finish(new NextResponse(r.response.body, { status: 200, headers }));
}
