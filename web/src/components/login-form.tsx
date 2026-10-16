"use client";
import { useState, type FormEvent } from "react";
import type { Problem } from "@/contract/types";
import { ChangePasswordForm } from "./change-password-form";
import { useI18n } from "./i18n-provider";
import { Banner } from "./kit/banner";
import { INPUT_CLASS, buttonClass } from "./kit/button";
import { MfaSetup } from "./mfa-setup";

type Step = "password" | "mfa" | "password_change_required" | "password_reset_by_support" | "no_web_access";

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
  const [show, setShow] = useState(false);
  const [recovery, setRecovery] = useState(false); // a recovery code has letters: the numeric keypad is for the 6-digit code

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
      else if (status === "password_reset_by_support") setStep("password_reset_by_support");
      else if (status === "signin_again") {
        setStep("password");
        setError(t("auth.password_change.signin_again"));
      } else if (status === "no_web_access") setStep("no_web_access");
    } catch {
      setError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  /** The change answered like a login: signed in, the TOTP step next, or a fresh sign-in. */
  async function afterChange(b: Record<string, unknown>) {
    const status = b.status as string;
    if (status === "ok") window.location.replace(next);
    else if (status === "mfa_required") setStep("mfa");
    else if (status === "signin_again") {
      setStep("password");
      setError(t("auth.password_change.signin_again"));
    } else if (status === "no_web_access") setStep("no_web_access");
  }

  function onPassword(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void run("/api/bff/login", { username: String(f.get("username") ?? "").trim(), password: String(f.get("password") ?? ""), remember: f.get("remember") === "on" });
  }
  function onMfa(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void run("/api/bff/mfa/verify", { code: String(f.get("code") ?? "").trim().toUpperCase() });
  }

  const input = `mt-1 ${INPUT_CLASS}`;
  const label = "block text-label text-secondary";
  const button = buttonClass("primary", "w-full");

  if (step === "password_reset_by_support" || step === "no_web_access") {
    return (
      <Banner tone="warning" role="alert" testId="login-blocked">
        {t(step === "no_web_access" ? "auth.no_web_access" : "auth.password_change_required")}
      </Banner>
    );
  }
  if (step === "password_change_required") {
    return (
      <div className="space-y-3" data-testid="login-password-change">
        <h2 className="text-heading text-primary">{t("auth.password_change.title")}</h2>
        <p className="text-body text-secondary">{t("auth.password_change.hint")}</p>
        <ChangePasswordForm endpoint="/api/bff/login/change-password" onResponse={(b) => void afterChange(b)} />
      </div>
    );
  }

  return (
    <div>
      {step === "password" ? (
        <form onSubmit={onPassword} className="space-y-4" data-testid="login-form">
          <label className={label}>
            {t("auth.username")}
            <input name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} required maxLength={40} className={input} />
          </label>
          <div className={label}>
            <label htmlFor="login-password">{t("auth.password")}</label>
            <div className="relative">
              <input id="login-password" name="password" type={show ? "text" : "password"} autoComplete="current-password" required maxLength={128} className={`${input} pe-20`} />
              <button type="button" onClick={() => setShow((v) => !v)} aria-pressed={show} data-testid="toggle-password" className="absolute inset-y-0 end-0 rounded-e-chip px-3 text-label text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:-outline-offset-2 outline-focus">
                {t(show ? "auth.hide" : "auth.show")}
              </button>
            </div>
          </div>
          <label className="flex min-h-(--ctl-h) items-center gap-2 text-body text-primary">
            <input type="checkbox" name="remember" defaultChecked={false} data-testid="remember-me" className="size-5 accent-accent-graphic" />
            {t("auth.remember")}
          </label>
          <button type="submit" disabled={busy} aria-busy={busy || undefined} className={button}>
            {t("auth.submit")}
          </button>
        </form>
      ) : (
        <form onSubmit={onMfa} className="space-y-4" data-testid="mfa-form">
          <h2 className="text-heading text-primary">{t("auth.mfa.title")}</h2>
          <p className="text-body text-secondary">{t("auth.mfa.hint")}</p>
          <label className={label}>
            {t("auth.mfa.code")}
            <input name="code" inputMode={recovery ? "text" : "numeric"} autoComplete="one-time-code" required maxLength={recovery ? 9 : 6} pattern={recovery ? undefined : "[0-9]{6}"} data-testid="mfa-code" className={`${input} tracking-widest`} />
          </label>
          <button type="submit" disabled={busy} aria-busy={busy || undefined} className={button}>
            {t("auth.mfa.submit")}
          </button>
          <button type="button" onClick={() => setRecovery(!recovery)} data-testid="mfa-toggle-recovery" className={buttonClass("text", "w-full")}>
            {t(recovery ? "auth.mfa.use_code" : "auth.mfa.use_recovery")}
          </button>
          <MfaSetup />
        </form>
      )}
      {error ? (
        <Banner tone="danger" role="alert" testId="login-error" className="mt-4">
          {error}
        </Banner>
      ) : null}
    </div>
  );
}
