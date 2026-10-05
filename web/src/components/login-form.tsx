"use client";
import { useState, type FormEvent } from "react";
import type { Problem } from "@/contract/types";
import { useI18n } from "./i18n-provider";

type Step = "password" | "mfa" | "password_change_required" | "no_web_access";

async function post(path: string, body: unknown): Promise<{ ok: boolean; data: Record<string, unknown> }> {
  const res = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), credentials: "same-origin" });
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { ok: res.ok, data };
}

export function LoginForm({ next }: { next: string }) {
  const { t, problem } = useI18n();
  const [step, setStep] = useState<Step>("password");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(path: string, body: unknown) {
    setBusy(true);
    setError(null);
    try {
      const r = await post(path, body);
      if (!r.ok) {
        setError(problem((r.data as Partial<Problem>).code));
        return;
      }
      const status = r.data.status as string;
      if (status === "ok") {
        // Full navigation so the server renders with the new cookies.
        window.location.replace(next);
        return;
      }
      if (status === "mfa_required") setStep("mfa");
      else if (status === "password_change_required") setStep("password_change_required");
      else if (status === "no_web_access") setStep("no_web_access");
    } catch {
      setError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  function onPassword(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void run("/api/bff/login", { username: String(f.get("username") ?? "").trim(), password: String(f.get("password") ?? "") });
  }
  function onMfa(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void run("/api/bff/mfa/verify", { code: String(f.get("code") ?? "").trim().toUpperCase() });
  }

  const input = "mt-1 w-full rounded border border-slate-300 px-3 py-2 focus:border-brand-600 focus:outline-none focus:ring-1 focus:ring-brand-600";
  const button = "w-full rounded bg-brand-600 px-4 py-2 font-semibold text-white hover:bg-brand-700 disabled:opacity-50";

  if (step === "password_change_required" || step === "no_web_access") {
    return (
      <p role="alert" data-testid="login-blocked" className="rounded border border-amber-300 bg-amber-50 p-3 text-amber-900">
        {t(step === "no_web_access" ? "auth.no_web_access" : "auth.password_change_required")}
      </p>
    );
  }

  return (
    <div>
      {step === "password" ? (
        <form onSubmit={onPassword} className="space-y-4" data-testid="login-form">
          <label className="block text-sm font-medium">
            {t("auth.username")}
            <input name="username" autoComplete="username" required maxLength={40} className={input} />
          </label>
          <label className="block text-sm font-medium">
            {t("auth.password")}
            <input name="password" type="password" autoComplete="current-password" required maxLength={128} className={input} />
          </label>
          <button type="submit" disabled={busy} className={button}>
            {t("auth.submit")}
          </button>
        </form>
      ) : (
        <form onSubmit={onMfa} className="space-y-4" data-testid="mfa-form">
          <h2 className="text-lg font-semibold">{t("auth.mfa.title")}</h2>
          <p className="text-sm text-slate-600">{t("auth.mfa.hint")}</p>
          <label className="block text-sm font-medium">
            {t("auth.mfa.code")}
            <input name="code" inputMode="text" autoComplete="one-time-code" required maxLength={9} className={`${input} tracking-widest`} />
          </label>
          <button type="submit" disabled={busy} className={button}>
            {t("auth.mfa.submit")}
          </button>
        </form>
      )}
      {error ? (
        <p role="alert" data-testid="login-error" className="mt-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </p>
      ) : null}
    </div>
  );
}
