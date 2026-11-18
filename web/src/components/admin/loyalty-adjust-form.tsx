"use client";
// Points adjustment of one programme (postProgrammeAdjustment / putProgrammeAdjustments, contract 1.23.0): a single-outlet form and a CSV upload with a
// dry-run preview, then the apply. Idempotent: the request / batch uuid is kept for a retry of the SAME body, so a resend never doubles points. The API judges
// scope, programme state and overdraw; this form only spares round trips and puts each error beside its field or row.
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { businessDate } from "@/lib/i18n";
import { ADJ_MAX_FILE_BYTES, ADJ_MAX_DEBITS, adjustFailureKey, debitRows, emptyAdjustForm, parseAdjustFile, signed, validateAdjust, type AdjustForm, type AdjustRow } from "@/lib/admin/loyalty-adjust";
import { nextAttempt } from "@/lib/admin/loyalty";
import { Field, inputClass } from "./kit/field";
import { ReasonField } from "./kit/reason-field";

type RowError = { row: number; column: string; code: string; balance_points?: number };
type Preview = { valid: boolean; replayed: boolean; rows: number; outlets: number; credit_points: number; debit_points: number; total_points: number; errors: RowError[]; error_count: number };
type Banner_ = { tone: "danger" | "success"; text: string } | null;

export function LoyaltyAdjustForm({ programmeId, active }: { programmeId: number; active: boolean }) {
  const { t, problem, number } = useI18n();
  const router = useRouter();
  const [mode, setMode] = useState<"single" | "bulk">("single");
  const [f, setF] = useState<AdjustForm>(emptyAdjustForm());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<Banner_>(null);
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);
  const set = <K extends keyof AdjustForm>(k: K, v: AdjustForm[K]) => setF((s) => ({ ...s, [k]: v }));
  const errText = (code: string, extra?: { balance?: number }) => t(`loy.adj.err.${code}` as MessageKey, { balance: number(extra?.balance ?? 0) });

  // bulk state
  const [fileName, setFileName] = useState("");
  const [rows, setRows] = useState<AdjustRow[]>([]);
  const [fileErrors, setFileErrors] = useState<{ line: number; column: string; code: string }[]>([]);
  const [bulkReason, setBulkReason] = useState("");
  const [bulkOverdraw, setBulkOverdraw] = useState(false);
  const [reasonError, setReasonError] = useState<string | undefined>();
  const [preview, setPreview] = useState<{ key: string; data: Preview } | null>(null);
  const batch = useRef<{ key: string; uuid: string } | null>(null);
  const bulkKey = JSON.stringify([rows, bulkReason.trim(), bulkOverdraw]);   // the PREVIEW is valid for exactly this
  const batchKey = JSON.stringify(rows);                                        // the batch uuid follows the ROWS only: an edited reason or box never makes a second batch of the same file

  async function call(method: "POST" | "PUT", body: unknown): Promise<{ ok: boolean; status: number; data: Record<string, unknown> }> {
    const res = await fetch(`/api/bff/admin/loyalty/${programmeId}/adjustments`, { method, headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(body) });
    return { ok: res.ok, status: res.status, data: (await res.json().catch(() => ({}))) as Record<string, unknown> };
  }
  function refused(status: number, data: Record<string, unknown>) {
    const p = data as Partial<Problem> & { context?: { balance_points?: number } };
    const errs = p.errors as { pointer: string; code: string }[] | undefined;
    const placed: Record<string, string> = {};
    for (const e of errs ?? []) {
      const field = /^body\.(outlet_id|points|expires_on|reason)$/.exec(e.pointer)?.[1];
      if (field) placed[field] = errText(e.code, { balance: p.context?.balance_points });
    }
    setErrors(placed);
    const key = adjustFailureKey(status, p.code, errs);
    setBanner({ tone: "danger", text: key ? t(key) : problem(p.code) });
  }

  async function submitSingle(e: React.FormEvent) {
    e.preventDefault();
    setBanner(null);
    const checked = validateAdjust(f, businessDate());
    setErrors(Object.fromEntries(checked.errors.map((x) => [x.field, errText(x.code)])));
    if (!checked.body) return;
    attempt.current = nextAttempt(attempt.current, JSON.stringify([checked.body.outlet_id, checked.body.points, checked.body.expires_on ?? null]));   // not the reason or the box: a lost answer plus an edited reason cannot post twice
    setBusy(true);
    try {
      const r = await call("POST", { request_uuid: attempt.current.uuid, ...checked.body });
      if (r.ok) {
        attempt.current = null;
        setBanner({ tone: "success", text: t(r.status === 200 ? "loy.adj.replayed" : "loy.adj.done") });
        setF(emptyAdjustForm());
        router.refresh();
      } else refused(r.status, r.data);
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  async function onFile(file: File | undefined) {
    setBanner(null); setPreview(null); setRows([]); setFileErrors([]);
    if (!file) { setFileName(""); return; }
    setFileName(file.name);
    let text: string;
    if (file.size > ADJ_MAX_FILE_BYTES) { setBanner({ tone: "danger", text: t("loy.adj.too_big") }); return; }
    try { text = await file.text(); } catch { setBanner({ tone: "danger", text: t("loy.adj.unreadable") }); return; }
    const parsed = parseAdjustFile(text, businessDate());
    if (parsed.tooMany) { setBanner({ tone: "danger", text: t("loy.adj.too_many") }); return; }
    if (parsed.rows.length === 0 && parsed.errors.length === 0) { setBanner({ tone: "danger", text: t("loy.adj.empty") }); return; }
    setRows(parsed.rows);
    setFileErrors(parsed.errors);
  }

  async function run(dry: boolean) {
    setBanner(null);
    const len = Array.from(bulkReason.trim()).length;
    if (len < 10 || len > 500) { setReasonError(errText(len < 10 ? "reason_short" : "reason_long")); return; }
    setReasonError(undefined);
    if (rows.length === 0 || fileErrors.length > 0) return;
    if (debitRows(rows) > ADJ_MAX_DEBITS) { setBanner({ tone: "danger", text: t("loy.adj.too_many_debits") }); return; }
    batch.current = nextAttempt(batch.current, batchKey);
    setBusy(true);
    try {
      const r = await call("PUT", { batch_uuid: batch.current.uuid, dry_run: dry, change_reason: bulkReason.trim(), ...(bulkOverdraw ? { allow_overdraw: true } : {}), rows });
      if (!r.ok) { refused(r.status, r.data); return; }
      if (dry) { setPreview({ key: bulkKey, data: r.data as unknown as Preview }); return; }
      batch.current = null;
      setBanner({ tone: "success", text: t("loy.adj.applied", { posted: number(Number(r.data.posted ?? 0)) }) });
      setRows([]); setFileName(""); setPreview(null); setBulkReason("");
      router.refresh();
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const fresh = preview !== null && preview.key === bulkKey ? preview.data : null;
  const canApply = fresh !== null && fresh.valid && !busy;
  const tabClass = (on: boolean) => `px-3 py-2 text-body-strong border-b-2 ${on ? "border-accent text-accent" : "border-transparent text-secondary"}`;

  if (!active) return <p role="note" className="text-body text-secondary" data-testid="adjust-inactive">{t("loy.adj.inactive")}</p>;
  return (
    <div className="space-y-4" data-testid="loyalty-adjust" aria-busy={busy}>
      <p className="text-body text-secondary">{t("loy.adj.intro")}</p>
      <div role="tablist" className="flex gap-2 border-b border-divider">
        <button type="button" role="tab" aria-selected={mode === "single"} className={tabClass(mode === "single")} onClick={() => { setMode("single"); setBanner(null); }} data-testid="adjust-tab-single">{t("loy.adj.single")}</button>
        <button type="button" role="tab" aria-selected={mode === "bulk"} className={tabClass(mode === "bulk")} onClick={() => { setMode("bulk"); setBanner(null); }} data-testid="adjust-tab-bulk">{t("loy.adj.bulk")}</button>
      </div>
      {banner ? <Banner tone={banner.tone} role={banner.tone === "danger" ? "alert" : "status"} testId="adjust-banner">{banner.text}</Banner> : null}
      {mode === "single" ? (
        <form onSubmit={submitSingle} noValidate className="space-y-4" data-testid="adjust-form">
          <div className="grid gap-4 md:grid-cols-3">
            <Field label={t("loy.adj.outlet")} htmlFor="adj-outlet" required error={errors.outlet_id}>
              <input id="adj-outlet" inputMode="numeric" value={f.outlet_id} onChange={(ev) => set("outlet_id", ev.target.value)} className={inputClass} autoComplete="off" />
            </Field>
            <Field label={t("loy.adj.points")} htmlFor="adj-points" required hint={t("loy.adj.points_hint")} error={errors.points}>
              <input id="adj-points" inputMode="numeric" value={f.points} onChange={(ev) => set("points", ev.target.value)} className={inputClass} autoComplete="off" />
            </Field>
            <Field label={t("loy.adj.expires")} htmlFor="adj-expires" hint={t("loy.adj.expires_hint")} error={errors.expires_on}>
              <input id="adj-expires" type="date" value={f.expires_on} onChange={(ev) => set("expires_on", ev.target.value)} className={inputClass} />
            </Field>
          </div>
          <ReasonField id="adj-reason" value={f.reason} onChange={(v) => set("reason", v)} error={errors.reason} />
          <label className="flex items-center gap-2 text-body text-primary"><input type="checkbox" checked={f.allow_overdraw} onChange={(ev) => set("allow_overdraw", ev.target.checked)} data-testid="adj-overdraw" />{t("loy.adj.allow_overdraw")}</label>
          <Button type="submit" aria-disabled={busy || undefined} testId="adj-submit">{t("loy.adj.submit")}</Button>
        </form>
      ) : (
        <div className="space-y-4" data-testid="adjust-bulk">
          <Field label={t("loy.adj.file")} htmlFor="adj-file" hint={t("loy.adj.file_hint")}>
            <input id="adj-file" type="file" accept=".csv,text/csv,text/plain" onChange={(ev) => void onFile(ev.target.files?.[0])} className={inputClass} data-testid="adj-file" />
          </Field>
          {fileName ? <p className="text-caption text-secondary [overflow-wrap:anywhere]" data-testid="adj-file-name">{fileName} · {number(rows.length)}</p> : null}
          {fileErrors.length > 0 ? <ProblemTable testId="adj-file-errors" total={fileErrors.length} items={fileErrors.slice(0, 50).map((e) => ({ line: e.line, column: e.column, text: errText(e.code) }))} /> : null}
          <ReasonField id="adj-bulk-reason" value={bulkReason} onChange={setBulkReason} error={reasonError} />
          <label className="flex items-center gap-2 text-body text-primary"><input type="checkbox" checked={bulkOverdraw} onChange={(ev) => setBulkOverdraw(ev.target.checked)} data-testid="adj-bulk-overdraw" />{t("loy.adj.allow_overdraw")}</label>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" onClick={() => void run(true)} aria-disabled={busy || rows.length === 0 || fileErrors.length > 0 || undefined} testId="adj-preview">{busy ? t("loy.adj.checking") : t("loy.adj.preview")}</Button>
            <Button type="button" onClick={() => void run(false)} aria-disabled={!canApply || undefined} disabled={!canApply} testId="adj-apply">{t("loy.adj.apply")}</Button>
          </div>
          {fresh ? (
            <div className="space-y-2" data-testid="adj-preview-result">
              {fresh.valid ? (
                <Banner tone="success" role="status" testId="adj-preview-ok">{t("loy.adj.preview_ok", { rows: number(fresh.rows), outlets: number(fresh.outlets), credit: signed(fresh.credit_points), debit: signed(fresh.debit_points), total: signed(fresh.total_points) })}</Banner>
              ) : (
                <Banner tone="danger" role="alert" testId="adj-preview-bad">{t("loy.adj.preview_bad", { count: number(fresh.error_count) })}</Banner>
              )}
              {fresh.replayed ? <p className="text-body text-secondary" data-testid="adj-preview-replayed">{t("loy.adj.preview_replayed")}</p> : null}
              {fresh.errors.length > 0 ? <ProblemTable testId="adj-preview-errors" total={fresh.error_count} items={fresh.errors.map((e) => ({ line: e.row + 1, column: e.column, text: errText(e.code, { balance: e.balance_points }) }))} /> : null}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

function ProblemTable({ items, total, testId }: { items: { line: number; column: string; text: string }[]; total: number; testId: string }) {
  const { t, number } = useI18n();
  return (
    <div className="space-y-1" data-testid={testId}>
      <table className="w-full text-body">
        <thead><tr className="text-label text-secondary text-start"><th className="text-start">{t("loy.adj.col.line")}</th><th className="text-start">{t("loy.adj.col.column")}</th><th className="text-start">{t("loy.adj.col.problem")}</th></tr></thead>
        <tbody>{items.map((e, i) => <tr key={`${e.line}-${e.column}-${i}`}><td>{number(e.line)}</td><td>{t(`loy.adj.field.${e.column}` as MessageKey)}</td><td className="[overflow-wrap:anywhere]">{e.text}</td></tr>)}</tbody>
      </table>
      {total > items.length ? <p className="text-caption text-secondary">{t("loy.adj.preview_more", { shown: number(items.length), count: number(total) })}</p> : null}
    </div>
  );
}
