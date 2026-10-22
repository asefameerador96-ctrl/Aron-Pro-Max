"use client";
// De-duplication of houses: groups of the same name in one territory, with activate and deactivate per member. A change is the
// ordinary house update (PATCH status with If-Match and a reason) through the CRUD proxy, so it is audited like any other.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { HouseGroup } from "@/lib/admin/house-dedupe";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

interface Pending {
  id: number;
  code: string;
  version: number;
  to: "active" | "inactive";
}

export function HouseDedupe({ groups, territories, canWrite }: { groups: HouseGroup[]; territories: Record<string, string>; canWrite: boolean }) {
  const { t, problem, number, dateTime } = useI18n();
  const router = useRouter();
  const [pending, setPending] = useState<Pending | null>(null);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);

  async function apply() {
    if (!pending) return;
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setReasonError(t("admin.reason.too_short"));
    setReasonError(undefined);
    setBusy(true);
    setBanner(null);
    try {
      const res = await fetch(`/api/bff/admin/houses/${pending.id}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ values: { status: pending.to }, reason: reason.trim(), version: pending.version }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t(pending.to === "inactive" ? "hd.done_deactivate" : "hd.done_activate", { code: pending.code }) });
        setPending(null);
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
    <div className="space-y-4" data-testid="house-groups">
      {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
      {groups.length === 0 ? <p data-testid="hd-none" className="rounded border border-slate-200 bg-white p-4 text-slate-700">{t("hd.none")}</p> : null}
      {groups.map((g) => {
        const keeperId = g.members.find((m) => m.status === "active")?.id;
        const open = g.activeCount > 1;
        return (
          <section key={`${g.parentId}|${g.key}`} data-testid="house-group" className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
            <h2 className="flex flex-wrap items-center gap-2 text-base font-semibold">
              {t("hd.group", { name: g.members[0]!.name, territory: territories[String(g.parentId)] ?? String(g.parentId ?? "") })}
              <span className={`rounded-full px-2 py-0.5 text-xs ${open ? "bg-amber-100 text-amber-900" : "bg-green-100 text-green-900"}`}>{open ? t("hd.group_open", { n: number(g.activeCount) }) : t("hd.group_done")}</span>
            </h2>
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-left"><tr><th scope="col" className="px-2 py-1">{t("hd.col.code")}</th><th scope="col" className="px-2 py-1">{t("hd.col.name")}</th><th scope="col" className="px-2 py-1">{t("hd.col.status")}</th><th scope="col" className="px-2 py-1">{t("hd.col.created")}</th>{canWrite ? <th scope="col" className="px-2 py-1"><span className="sr-only">{t("common.actions")}</span></th> : null}</tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {g.members.map((m) => (
                    <tr key={m.id} data-testid={`house-${m.id}`}>
                      <td className="px-2 py-1.5">{m.code}</td>
                      <td className="px-2 py-1.5">{m.name}{m.id === keeperId && open ? <span className="ml-2 text-xs text-green-800">· {t("hd.keeper")}</span> : null}</td>
                      <td className="px-2 py-1.5">{t(m.status === "active" ? "entity.status.active" : "entity.status.inactive")}</td>
                      <td className="px-2 py-1.5">{m.created_at ? dateTime(m.created_at) : ""}</td>
                      {canWrite ? (
                        <td className="px-2 py-1.5">
                          <button type="button" data-testid={`toggle-${m.id}`} onClick={() => { setPending({ id: m.id, code: m.code, version: m.version, to: m.status === "active" ? "inactive" : "active" }); setReason(""); setReasonError(undefined); setBanner(null); }} className="min-h-11 rounded border border-slate-300 px-3 py-1 hover:bg-slate-100">
                            {t(m.status === "active" ? "hd.deactivate" : "hd.activate")}
                          </button>
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {pending && g.members.some((m) => m.id === pending.id) ? (
              <div data-testid="hd-confirm" className="space-y-2 rounded border border-slate-200 bg-slate-50 p-3">
                <p className="font-medium">{t(pending.to === "inactive" ? "hd.confirm_deactivate" : "hd.confirm_activate", { code: pending.code })}</p>
                <ReasonField value={reason} onChange={setReason} error={reasonError} />
                <div className="flex gap-2">
                  <button type="button" data-testid="hd-apply" disabled={busy} onClick={() => void apply()} className="min-h-11 rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t(pending.to === "inactive" ? "hd.deactivate" : "hd.activate")}</button>
                  <button type="button" onClick={() => setPending(null)} className="min-h-11 rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100">{t("hd.cancel")}</button>
                </div>
              </div>
            ) : null}
          </section>
        );
      })}
    </div>
  );
}
