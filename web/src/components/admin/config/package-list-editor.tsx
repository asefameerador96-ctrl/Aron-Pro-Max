"use client";
// N-046: edit one package list (blocked, allowed or always allowed) and save it as ONE config change with a reason.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { PACKAGE_EXAMPLE, groupOf, validatePackage } from "@/lib/admin/packages";
import type { MessageKey } from "@/lib/i18n";
import { inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

export function PackageListEditor({ keyName, title, initial, max, canWrite }: { keyName: string; title: string; initial: string[]; max: number; canWrite: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [list, setList] = useState(initial);
  const [draft, setDraft] = useState("");
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const changed = list.length !== initial.length || list.some((p, i) => p !== initial[i]);

  function add() {
    const v = validatePackage(draft, list, max);
    if (v) return setErr(t(v === "invalid" ? "ab.invalid" : v === "duplicate" ? "ab.duplicate" : "ab.too_many"));
    setErr(null);
    setList([...list, draft.trim()]);
    setDraft("");
  }

  async function save() {
    setBanner(null);
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setErr(t("admin.reason.too_short"));
    setErr(null);
    setBusy(true);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op: "config.change", body: { changes: [{ key: keyName, scope_type: "global", scope_id: 0, value: list }] }, reason: reason.trim() }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: { status?: string } } & Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: `${t("cfgc.change.sent")} ${t(`cfgc.status.${data.data?.status ?? "applied"}` as MessageKey)}` });
        setReason("");
        router.refresh();
      } else setBanner({ ok: false, text: problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={`pkg-${keyName}`}>
      <h2 className="text-lg font-semibold">{title}</h2>
      <ul className="divide-y divide-slate-100 text-sm">
        {list.map((p) => (
          <li key={p} className="flex items-center justify-between gap-2 py-1.5">
            <span className="font-mono">{p}</span>
            <span className="text-slate-500">{t(`ab.group.${groupOf(p)}` as MessageKey)}</span>
            {canWrite ? (
              <button type="button" onClick={() => setList(list.filter((x) => x !== p))} className="rounded border border-red-300 px-2 py-0.5 text-xs text-red-700 hover:bg-red-50">
                {t("ab.remove")}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {canWrite ? (
        <>
          <div className="flex gap-2">
            <input aria-label={t("ab.add")} value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} placeholder={PACKAGE_EXAMPLE} maxLength={120} className={inputClass} />
            <button type="button" onClick={add} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
              {t("ab.add.button")}
            </button>
          </div>
          {err ? <p role="alert" className="text-xs font-medium text-red-700">{err}</p> : null}
          {changed ? (
            <>
              <p className="text-xs text-amber-700">{t("ab.changed")}</p>
              <ReasonField value={reason} onChange={setReason} id={`r-${keyName}`} />
              <button type="button" disabled={busy} onClick={save} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
                {t("ab.save")}
              </button>
            </>
          ) : null}
          {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
        </>
      ) : null}
    </section>
  );
}
