"use client";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import { callMasterOp } from "./master-op-client";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface TeamUser {
  id: number;
  username: string;
  full_name: string;
  role: "SR" | "AMO";
  status: string;
}
type Action = "reset_password" | "unlock";
interface Done {
  action: Action;
  password: string | null;
  expires: string | null;
}

/** Reset password or unlock one SR or AMO, each with a mandatory reason. The temporary password is shown once, here. */
export function TeamCredentials({ users }: { users: TeamUser[] }) {
  const { t, dateTime, problem } = useI18n();
  const [pending, setPending] = useState<{ user: TeamUser; action: Action } | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<(Done & { user: TeamUser }) | null>(null);

  async function submit() {
    if (!pending) return;
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setError(t("admin.reason.too_short"));
    setBusy(true);
    setError(null);
    const r = await callMasterOp<{ temporary_password?: string | null; temporary_password_expires_at?: string | null }>("credential.manage", { params: { id: pending.user.id }, body: { action: pending.action }, reason: reason.trim() });
    setBusy(false);
    if (!r.ok) return setError(problem(r.problem.code));
    setDone({ user: pending.user, action: pending.action, password: r.data?.temporary_password ?? null, expires: r.data?.temporary_password_expires_at ?? null });
    setPending(null);
    setReason("");
  }

  return (
    <div className="space-y-4" data-testid="team">
      {users.length === 0 ? (
        <p className="text-slate-600">{t("common.empty")}</p>
      ) : (
        <table className="w-full text-left text-sm" data-testid="data-table" aria-label={t("menu.admin.team")}>
          <thead>
            <tr className="border-b border-slate-200 text-slate-600">
              <th className="p-2">{t("entity.field.username")}</th>
              <th className="p-2">{t("entity.field.full_name")}</th>
              <th className="p-2">{t("entity.field.role")}</th>
              <th className="p-2" />
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} className="border-b border-slate-100">
                <td className="p-2">{u.username}</td>
                <td className="p-2 break-words">{u.full_name}</td>
                <td className="p-2">{t(`role.${u.role}` as MessageKey)}</td>
                <td className="space-x-3 p-2">
                  {(["reset_password", "unlock"] as const).map((a) => (
                    <button key={a} type="button" data-testid={`${a}-${u.id}`} onClick={() => { setPending({ user: u, action: a }); setReason(""); setError(null); setDone(null); }} aria-label={`${t(`team.${a}` as MessageKey)}: ${u.username}`} className="text-brand-600 underline">
                      {t(`team.${a}` as MessageKey)}
                    </button>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {pending ? (
        <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4" aria-labelledby="team-form-title">
          <h2 id="team-form-title" className="font-semibold">{`${t(`team.${pending.action}` as MessageKey)}: ${pending.user.username}`}</h2>
          {pending.action === "reset_password" ? <p className="text-sm text-slate-600">{t("team.reset_note")}</p> : null}
          <ReasonField value={reason} onChange={setReason} error={error} />
          <div className="flex gap-2">
            <button type="button" disabled={busy} onClick={submit} data-testid="team-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
            <button type="button" disabled={busy} onClick={() => setPending(null)} className="rounded border border-slate-300 px-4 py-2 text-sm">{t("common.cancel")}</button>
          </div>
        </section>
      ) : null}
      {done ? (
        <section role="status" data-testid="team-done" className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-4">
          <p className="font-semibold text-amber-900">{`${t(`team.${done.action}.ok` as MessageKey)}: ${done.user.username}`}</p>
          {done.password ? (
            <>
              <p className="text-sm text-amber-900">{t("admin.shown_once")}</p>
              <p className="font-mono text-lg" data-testid="team-password">{done.password}</p>
              {done.expires ? <p className="text-sm text-slate-700">{t("team.expires", { at: dateTime(done.expires) })}</p> : null}
              <button type="button" onClick={() => setDone(null)} className="rounded bg-brand-600 px-3 py-1 text-sm font-semibold text-white">{t("common.done")}</button>
            </>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
