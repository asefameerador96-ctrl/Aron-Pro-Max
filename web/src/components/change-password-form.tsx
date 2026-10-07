"use client";
import { useState, type FormEvent } from "react";
import { checkPasswords, isValid, type PasswordCheck } from "@/lib/auth/password-policy";
import type { Problem } from "@/contract/types";
import { useI18n } from "./i18n-provider";

type Field = "old" | "next" | "confirm";

/** Old, New and Confirm fields (also the forced change at login: `endpoint` and `onResponse` then continue the login) with the policy enforced before the request and authored errors per field (F-WEB-033). */
export function ChangePasswordForm({ endpoint = "/api/bff/password", onResponse }: { endpoint?: string; onResponse?: (body: Record<string, unknown>) => void } = {}) {
  const { t, problem } = useI18n();
  const [errors, setErrors] = useState<PasswordCheck>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const oldPw = String(f.get("old") ?? "");
    const newPw = String(f.get("next") ?? "");
    const check = checkPasswords(oldPw, newPw, String(f.get("confirm") ?? ""));
    setErrors(check);
    setServerError(null);
    if (!isValid(check)) return;
    setBusy(true);
    try {
      const res = await fetch(endpoint, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ current_password: oldPw, new_password: newPw }) });
      if (res.ok) {
        form.reset();
        if (onResponse) onResponse((await res.json().catch(() => ({}))) as Record<string, unknown>);
        else setDone(true);
        return;
      }
      const p = (await res.json().catch(() => ({}))) as Partial<Problem>;
      const pointer = p.errors?.find((x) => x.pointer === "/current_password");
      if (pointer) setErrors({ old: "wrong_current" });
      else setServerError(problem(p.code));
    } catch {
      setServerError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  const input = "mt-1 w-full rounded border border-slate-300 px-3 py-2";
  const field = (name: Field, label: "credentials.old" | "credentials.new" | "credentials.confirm", auto: string) => (
    <label className="block text-sm font-medium">
      {t(label)}
      <input name={name} type="password" autoComplete={auto} maxLength={128} aria-invalid={errors[name] ? true : undefined} aria-describedby={errors[name] ? `err-${name}` : undefined} className={input} data-testid={`pw-${name}`} />
      {errors[name] ? (
        <span id={`err-${name}`} role="alert" className="mt-1 block text-sm text-red-800" data-testid={`pw-error-${name}`}>
          {t(`credentials.error.${errors[name]}` as "credentials.error.required")}
        </span>
      ) : null}
    </label>
  );
  return (
    <form onSubmit={onSubmit} noValidate className="max-w-md space-y-4" data-testid="password-form">
      {field("old", "credentials.old", "current-password")}
      {field("next", "credentials.new", "new-password")}
      {field("confirm", "credentials.confirm", "new-password")}
      <button type="submit" disabled={busy} className="rounded bg-brand-700 px-4 py-2 font-semibold text-white disabled:opacity-60">
        {t("credentials.submit")}
      </button>
      {serverError ? (
        <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-900" data-testid="pw-server-error">
          {serverError}
        </p>
      ) : null}
      {done ? (
        <p role="status" className="rounded bg-emerald-50 p-3 text-sm text-emerald-900" data-testid="pw-done">
          {t("credentials.done")}
        </p>
      ) : null}
    </form>
  );
}
