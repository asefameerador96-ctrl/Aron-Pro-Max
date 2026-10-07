"use client";
// F-ADM-064: one role's menus and actions as data. Saving creates a C3 change request (never an immediate grant).
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { MenuPermission } from "@/lib/admin/types";
import type { MessageKey } from "@/lib/i18n";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

const MENU_EXAMPLE = "web_entry";
export const ACTIONS = ["view", "create", "edit", "approve", "export", "void"] as const;
type Action = (typeof ACTIONS)[number];

export function PermissionEditor({ role, menuIds: knownMenus, initial, canWrite }: { role: string; menuIds: string[]; initial: MenuPermission[]; canWrite: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [menuIds, setMenuIds] = useState<string[]>(knownMenus);
  const [newMenu, setNewMenu] = useState("");
  const [grid, setGrid] = useState<Record<string, Set<Action>>>(() => Object.fromEntries(knownMenus.map((m) => [m, new Set((initial.find((x) => x.menu_id === m)?.actions ?? []) as Action[])])));
  const [reason, setReason] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const toggle = (m: string, a: Action) =>
    setGrid((g) => {
      const s = new Set(g[m]);
      if (s.has(a)) s.delete(a);
      else s.add(a);
      // every action beyond view implies the menu can be seen
      if (s.size > 0) s.add("view");
      return { ...g, [m]: s };
    });

  function addMenu() {
    const id = newMenu.trim();
    if (!/^[a-z][a-z0-9_.]{1,60}$/.test(id) || menuIds.includes(id)) return setErr(t("error.field.invalid"));
    setMenuIds((s) => [...s, id]);
    setGrid((g) => ({ ...g, [id]: new Set<Action>() }));
    setNewMenu("");
    setErr(null);
  }

  async function save() {
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setErr(t("admin.reason.too_short"));
    setErr(null);
    setBusy(true);
    setBanner(null);
    const menus: MenuPermission[] = menuIds.filter((m) => grid[m]!.size > 0).map((m) => ({ menu_id: m, actions: ACTIONS.filter((a) => grid[m]!.has(a)) }));
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "permissions.put", params: { role }, body: { menus }, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("pm.saved") });
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
    <div className="space-y-3" data-testid={`perm-${role}`}>
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left">
            <tr>
              <th className="px-2 py-1">{t("pm.menu")}</th>
              {ACTIONS.map((a) => (
                <th key={a} className="px-2 py-1">{t(`pm.action.${a}` as MessageKey)}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {menuIds.map((m) => (
              <tr key={m}>
                <td className="px-2 py-1 font-mono text-xs">{m}</td>
                {ACTIONS.map((a) => (
                  <td key={a} className="px-2 py-1">
                    <input type="checkbox" aria-label={`${m} ${a}`} checked={grid[m]!.has(a)} disabled={!canWrite} onChange={() => toggle(m, a)} className="h-4 w-4" />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {canWrite ? (
        <>
          <div className="flex items-end gap-2">
            <input aria-label={t("pm.add_menu")} name="menu_id" value={newMenu} onChange={(e) => setNewMenu(e.target.value)} placeholder={MENU_EXAMPLE} className="rounded border border-slate-300 px-2 py-1.5 text-sm" />
            <button type="button" onClick={addMenu} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">{t("pm.add")}</button>
          </div>
          <p className="text-xs text-[var(--warning)]">{t("pm.c3")}</p>
          <ReasonField value={reason} onChange={setReason} error={err} id={`reason-${role}`} />
          {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-[color-mix(in_srgb,var(--success)_14%,transparent)] text-[var(--success)]" : "bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] text-[var(--danger)]"}`}>{banner.text}</p> : null}
          <button type="button" disabled={busy} onClick={save} className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("pm.request")}</button>
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("cfgc.read_only")}</p>
      )}
    </div>
  );
}
