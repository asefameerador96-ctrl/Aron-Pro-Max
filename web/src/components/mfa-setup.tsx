"use client";
// First sign-in of an administrator with no authenticator: start the enrolment here, scan the QR (or type the key), keep the
// recovery codes, then enter the first code in the form below. Everything stays in this component's state; nothing is stored.
import { useState } from "react";
import type { Problem } from "@/contract/types";
import { QrCode } from "./admin/config/qr-code";
import { useI18n } from "./i18n-provider";

interface Enrolment {
  otpauth_uri: string;
  recovery_codes: string[];
}

const secretOf = (uri: string): string => /[?&]secret=([A-Za-z2-7]+)/.exec(uri)?.[1] ?? "";

export function MfaSetup() {
  const { t, problem } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<Enrolment | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/bff/mfa/enrol", { method: "POST", credentials: "same-origin" });
      const body = (await res.json().catch(() => ({}))) as Partial<Enrolment> & Partial<Problem>;
      if (res.ok && body.otpauth_uri && body.recovery_codes) setData({ otpauth_uri: body.otpauth_uri, recovery_codes: body.recovery_codes });
      else setError(res.status === 409 ? t("auth.mfa.setup.already") : problem(body.code));
    } catch {
      setError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  if (!data) {
    return (
      <div className="mt-4 space-y-2" data-testid="mfa-setup-start">
        <p className="text-sm text-slate-600">{t("auth.mfa.setup.hint")}</p>
        <button type="button" disabled={busy} onClick={start} data-testid="mfa-setup" className="rounded border border-brand-600 px-4 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-50 disabled:opacity-50">
          {t("auth.mfa.setup")}
        </button>
        {error ? <p role="alert" data-testid="mfa-setup-error" className="text-sm text-red-800">{error}</p> : null}
      </div>
    );
  }
  return (
    <section role="status" aria-live="polite" className="mt-4 space-y-3 rounded border border-amber-300 bg-amber-50 p-3" data-testid="mfa-setup-result">
      <h3 className="font-semibold">{t("auth.mfa.setup.scan")}</h3>
      <QrCode text={data.otpauth_uri} label={t("auth.mfa.setup.scan")} size={220} />
      <p className="text-sm">
        {t("auth.mfa.setup.key")} <code data-testid="mfa-secret" className="break-all font-mono">{secretOf(data.otpauth_uri)}</code>
      </p>
      <h3 className="font-semibold">{t("auth.mfa.setup.codes")}</h3>
      <p className="text-sm">{t("auth.mfa.setup.codes_hint")}</p>
      <ul className="grid grid-cols-2 gap-1 font-mono text-sm" data-testid="mfa-recovery-codes">
        {data.recovery_codes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
    </section>
  );
}
