"use client";
// Route monthly targets: read an Excel sheet in the browser, report problems row by row, preview with the server (dry run), then
// commit with a reason. One batch uuid per file: a retried commit replays and never doubles a number.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { HEADERS, parseTargetSheet, templateCells, type ParsedSheet, type RowIssue, type TargetPreview } from "@/lib/admin/route-targets";
import { inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

const MAX_FILE = 5 * 1024 * 1024;
const SHOWN = 200;

async function callOp(op: string, body: Record<string, unknown>, reason?: string): Promise<{ ok: true; data: unknown } | { ok: false; status: number; problem: Partial<Problem> }> {
  try {
    const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op, body, ...(reason ? { reason } : {}) }) });
    const json = (await res.json().catch(() => ({}))) as { data?: unknown } & Partial<Problem>;
    return res.ok ? { ok: true, data: json.data } : { ok: false, status: res.status, problem: json };
  } catch {
    return { ok: false, status: 0, problem: { code: "ERR_SERVICE_UNAVAILABLE" } };
  }
}

export function RouteTargetsUpload({ currentMonth }: { currentMonth: string }) {
  const { t, problem, number } = useI18n();
  const router = useRouter();
  const [parsed, setParsed] = useState<ParsedSheet | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [batch, setBatch] = useState(() => crypto.randomUUID());
  const [preview, setPreview] = useState<TargetPreview | null>(null);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [inputKey, setInputKey] = useState(0);

  function reset() {
    setParsed(null);
    setPreview(null);
    setFileError(null);
    setBatch(crypto.randomUUID());
    setReason("");
    setInputKey((k) => k + 1);
  }

  async function onFile(file: File | undefined) {
    setBanner(null);
    setPreview(null);
    setParsed(null);
    setFileError(null);
    setBatch(crypto.randomUUID()); // a new file is a new batch
    if (!file) return;
    if (!/\.xlsx$/i.test(file.name)) return setFileError(t("rt.bad_type"));
    if (file.size > MAX_FILE) return setFileError(t("rt.too_big"));
    try {
      const { readSheet } = await import("read-excel-file/browser");
      setParsed(parseTargetSheet((await readSheet(file)) as unknown[][], currentMonth));
    } catch {
      setFileError(t("rt.read_failed"));
    }
  }

  async function downloadTemplate() {
    const { default: writeExcelFile } = await import("write-excel-file/browser");
    const blob = await writeExcelFile(templateCells(currentMonth).map((r) => r.map((value) => ({ value })))).toBlob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "route-targets-template.xlsx";
    a.click();
    URL.revokeObjectURL(a.href);
  }

  const body = (dry: boolean) => ({ batch_uuid: batch, rows: parsed!.rows, dry_run: dry });

  async function check() {
    if (!parsed || parsed.issues.length || parsed.rows.length === 0) return;
    setBusy(true);
    setBanner(null);
    const r = await callOp("route-targets.validate", body(true));
    setBusy(false);
    if (r.ok) setPreview(r.data as TargetPreview);
    else setBanner({ ok: false, text: problem(r.problem.code) });
  }

  async function commit() {
    if (!parsed || !preview || preview.errors.length) return;
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setReasonError(t("admin.reason.too_short"));
    setReasonError(undefined);
    setBusy(true);
    setBanner(null);
    const r = await callOp("route-targets.commit", body(false), reason.trim());
    setBusy(false);
    if (r.ok) {
      const d = r.data as { rows?: number; replayed?: boolean } | null;
      setBanner({ ok: true, text: d?.replayed ? t("rt.replayed") : t("rt.committed", { rows: d?.rows ?? parsed.rows.length }) });
      reset();
      router.refresh();
    } else setBanner({ ok: false, text: problem(r.problem.code) });
  }

  const issueText = (i: RowIssue) => t(`rt.problem.${i.code}` as "rt.problem.invalid");
  const colText = (c: string) => t(`rt.col.${c}` as "rt.col.route");
  const sheetRowOf = (n: number) => parsed?.sheetRows[n - 1] ?? n;
  const serverIssues = preview?.errors ?? [];

  return (
    <div className="space-y-4" data-testid="rt-upload">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="mb-1 block font-medium">{t("rt.pick")}</span>
          <input key={inputKey} id="rt-file" data-testid="rt-file" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={(e) => void onFile(e.target.files?.[0])} className={inputClass} />
        </label>
        <button type="button" data-testid="rt-template" onClick={() => void downloadTemplate()} className="min-h-11 rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100">{t("rt.template")}</button>
      </div>
      <p className="text-xs text-slate-600">{t("rt.columns")} <code>{Object.values(HEADERS).join(", ")}</code></p>
      {fileError ? <p role="alert" data-testid="rt-file-error" className="rounded bg-red-50 p-3 text-sm text-red-800">{fileError}</p> : null}

      {parsed && parsed.issues.length > 0 ? (
        <section className="space-y-2" data-testid="rt-issues" aria-labelledby="rt-issues-h">
          <h2 id="rt-issues-h" className="text-lg font-semibold text-red-800">{t("rt.issues_title", { n: parsed.issues.length })}</h2>
          <p className="text-sm text-slate-700">{t("rt.issues_note")}</p>
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-left"><tr><th scope="col" className="px-3 py-2">{t("rt.col.row")}</th><th scope="col" className="px-3 py-2">{t("rt.col.column")}</th><th scope="col" className="px-3 py-2">{t("rt.col.problem")}</th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {parsed.issues.slice(0, SHOWN).map((i, k) => (
                  <tr key={k}><td className="px-3 py-1.5 tabular-nums">{number(i.row)}</td><td className="px-3 py-1.5">{colText(i.column)}</td><td className="px-3 py-1.5">{issueText(i)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
          {parsed.issues.length > SHOWN ? <p className="text-sm text-slate-600">{t("rt.issues_more", { n: parsed.issues.length - SHOWN })}</p> : null}
        </section>
      ) : null}

      {parsed && parsed.issues.length === 0 ? (
        <section className="space-y-3" data-testid="rt-read-ok">
          <p role="status" className="text-sm font-medium text-green-800">{t("rt.read_ok", { rows: parsed.rows.length })}</p>
          {!preview ? (
            <button type="button" data-testid="rt-check" disabled={busy} onClick={() => void check()} className="min-h-11 rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{busy ? t("rt.checking") : t("rt.check")}</button>
          ) : null}
        </section>
      ) : null}

      {preview ? (
        <section className="space-y-3" data-testid="rt-preview">
          <p data-testid="rt-summary" className="text-sm font-medium">{t("rt.summary", { rows: number(preview.rows), routes: number(preview.routes), skus: number(preview.skus), months: preview.months.join(", "), total: number(preview.total_qty_base) })}</p>
          {preview.replaces > 0 ? <p data-testid="rt-replaces" className="text-sm text-amber-800">{t("rt.replaces", { n: number(preview.replaces) })}</p> : null}
          {serverIssues.length > 0 ? (
            <div data-testid="rt-server-issues" className="space-y-2">
              <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{t("rt.server_issues", { n: number(serverIssues.length) })}</p>
              <ul className="list-disc pl-5 text-sm">
                {serverIssues.slice(0, SHOWN).map((e, k) => (
                  <li key={k}>{t("rt.col.row")} {number(sheetRowOf(e.row))} · {colText(e.column)} · {t(`rt.problem.${e.code}` as "rt.problem.invalid")}</li>
                ))}
              </ul>
            </div>
          ) : (
            <>
              <ReasonField value={reason} onChange={setReason} error={reasonError} />
              <button type="button" data-testid="rt-commit" disabled={busy} onClick={() => void commit()} className="min-h-11 rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("rt.commit")}</button>
            </>
          )}
          <button type="button" onClick={reset} className="min-h-11 rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100">{t("rt.reset")}</button>
        </section>
      ) : null}

      {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
    </div>
  );
}
