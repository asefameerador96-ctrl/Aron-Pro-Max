"use client";
// Module grants (ADMIN) and brand scope (BRAND) of one user (docs/34 s1). One PUT with If-Match (the grants version) and a
// mandatory reason; the server derives the token claims from it, so a change shows at the user's next token.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { accessKind, accessRules, GRANT_MODULES, levelOf, modulesBody, MODULE_KEYS, type GrantLevel, type GrantModule, type UserAccess } from "@/lib/admin/access-grants";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface BrandOption {
  id: number;
  label: string;
}

const LEVELS: readonly GrantLevel[] = ["none", "r", "w"];

export function UserAccessEditor({ access, brands, canWrite }: { access: UserAccess; brands: BrandOption[]; canWrite: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const kind = accessKind(access.role);
  const [levels, setLevels] = useState<Record<GrantModule, GrantLevel>>(() => Object.fromEntries(GRANT_MODULES.map((m) => [m, levelOf(access.modules, m)])) as Record<GrantModule, GrantLevel>);
  const [picked, setPicked] = useState<Set<number>>(() => new Set(access.brand_ids));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);

  async function save() {
    setBanner(null);
    const body = kind === "modules" ? { modules: modulesBody(levels) } : { brand_ids: [...picked].sort((a, b) => a - b) };
    const e: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    for (const p of accessRules(access.role, body)) if (p.pointer === "/brand_ids" && p.code === "required") e.brands = t("access.brands.none_selected");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "user-access.put", params: { id: access.user_id }, body, reason: reason.trim(), version: access.version }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("access.saved") });
        setReason("");
        router.refresh();
      } else setBanner({ ok: false, text: res.status === 412 ? t("access.stale") : res.status === 503 ? t("access.unavailable") : problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  if (kind === "none") {
    return (
      <div data-testid="access-none" className="rounded border border-slate-200 bg-white p-4 text-slate-700">
        <p className="font-medium">{t("access.none.title")}</p>
        <p className="text-sm">{t("access.none.body")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4" data-testid="access-editor">
      {kind === "modules" ? (
        <section className="space-y-2" aria-labelledby="mod-h">
          <h2 id="mod-h" className="text-lg font-semibold">{t("access.modules.title")}</h2>
          <p className="text-sm text-slate-600">{t("access.modules.intro")}</p>
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-left">
                <tr>
                  <th scope="col" className="px-3 py-2">{t("access.module")}</th>
                  {LEVELS.map((l) => (<th key={l} scope="col" className="px-3 py-2">{t(`access.level.${l}` as "access.level.r")}</th>))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {GRANT_MODULES.map((m) => (
                  <tr key={m} data-testid={`grant-${m}`}>
                    <th scope="row" className="px-3 py-2 text-left font-normal">{t(MODULE_KEYS[m])}</th>
                    {LEVELS.map((l) => (
                      <td key={l} className="px-3 py-2">
                        <input type="radio" name={`grant-${m}`} value={l} checked={levels[m] === l} disabled={!canWrite} aria-label={`${t(MODULE_KEYS[m])}: ${t(`access.level.${l}` as "access.level.r")}`} onChange={() => setLevels((s) => ({ ...s, [m]: l }))} className="h-5 w-5" />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : (
        <section className="space-y-2" aria-labelledby="brand-h">
          <h2 id="brand-h" className="text-lg font-semibold">{t("access.brands.title")}</h2>
          <p className="text-sm text-slate-600">{t("access.brands.intro")}</p>
          {brands.length === 0 ? <p data-testid="brands-empty" className="text-slate-600">{t("access.brands.empty")}</p> : null}
          <ul className="grid gap-2 sm:grid-cols-2" data-testid="brand-list">
            {brands.map((b) => (
              <li key={b.id}>
                <label className="flex min-h-11 items-center gap-2 rounded border border-slate-200 bg-white px-3 py-2">
                  <input type="checkbox" checked={picked.has(b.id)} disabled={!canWrite} data-testid={`brand-${b.id}`} onChange={(ev) => setPicked((s) => { const n = new Set(s); if (ev.target.checked) n.add(b.id); else n.delete(b.id); return n; })} className="h-5 w-5" />
                  <span>{b.label}</span>
                </label>
              </li>
            ))}
          </ul>
          {errors.brands ? <p role="alert" data-testid="error-brands" className="text-xs font-medium text-red-700">{errors.brands}</p> : null}
        </section>
      )}
      {canWrite ? (
        <>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          {banner ? (
            <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p>
          ) : null}
          <button type="button" disabled={busy} onClick={save} data-testid="access-save" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("access.super_only")}</p>
      )}
    </div>
  );
}
