"use client";
// PII reveal (docs/21 s4.5, POST /v1/auth/reauth via /api/bff/reauth): a step-up with the password (and the authenticator code for
// an MFA user) opens a window until `pii_until`; the countdown runs from the server's time and the page re-masks when it ends.
// Only a role whose /v1/me lists "pii.reveal" gets this control; masked values are never an error.
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Button } from "@/components/kit/button";

export interface PiiRevealLabels {
  open: string;
  password: string;
  code: string;
  submit: string;
  cancel: string;
  active: string; // "{time}" left
  bad: string;
  locked: string;
  resignin: string;
  limited: string;
  failed: string;
}

const input = "mt-1 w-full rounded border border-slate-300 px-3 py-2";

/** "m:ss" left until `until` (epoch ms), never negative. */
export function leftText(until: number, now: number): string {
  const s = Math.max(0, Math.ceil((until - now) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** Message key for a refused reveal (one message for a wrong password or code, as the server sends one code for both). */
export function revealError(status: number, code: string | undefined): keyof PiiRevealLabels {
  if (code === "ERR_AUTH_ACCOUNT_LOCKED") return "locked";
  if (code === "ERR_AUTH_MFA_INVALID") return "resignin";
  if (status === 429 || code === "ERR_RATE_LIMITED") return "limited";
  if (status === 401 || status === 400) return "bad";
  return "failed";
}

export function PiiReveal({ piiUntil, needsCode, labels }: { piiUntil: string | null; needsCode: boolean; labels: PiiRevealLabels }) {
  const router = useRouter();
  const until = piiUntil ? Date.parse(piiUntil) : NaN;
  const [now, setNow] = useState(() => Date.now());
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<keyof PiiRevealLabels | null>(null);
  const active = Number.isFinite(until) && until > now;

  useEffect(() => {
    if (!Number.isFinite(until)) return;
    const id = window.setInterval(() => {
      const t = Date.now();
      setNow(t);
      if (t >= until) {
        window.clearInterval(id);
        router.refresh(); // the server no longer reveals: re-render masked
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, [until, router]);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setErr(null);
    try {
      const res = await fetch("/api/bff/reauth", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: String(f.get("password") ?? ""), code: needsCode ? String(f.get("code") ?? "") : null }) });
      if (res.ok) {
        setOpen(false);
        router.refresh();
        return;
      }
      const p = (await res.json().catch(() => null)) as { code?: string } | null;
      setErr(revealError(res.status, p?.code));
    } catch {
      setErr("failed");
    } finally {
      setBusy(false);
    }
  }

  if (active) return <p className="text-caption text-secondary" role="status" data-testid="pii-active">{labels.active.replace("{time}", leftText(until, now))}</p>;
  return (
    <div data-testid="pii-reveal">
      {open ? (
        <form onSubmit={submit} className="max-w-sm space-y-2" aria-label={labels.open}>
          <label className="block text-sm">
            {labels.password}
            <input name="password" type="password" required maxLength={128} autoComplete="current-password" className={input} data-testid="pii-password" />
          </label>
          {needsCode ? (
            <label className="block text-sm">
              {labels.code}
              <input name="code" inputMode="numeric" required pattern="[0-9]{6}" maxLength={6} autoComplete="one-time-code" className={input} data-testid="pii-code" />
            </label>
          ) : null}
          {err ? <p role="alert" className="text-sm text-danger-on-container" data-testid="pii-error">{labels[err]}</p> : null}
          <div className="flex gap-2">
            <Button type="submit" disabled={busy} testId="pii-submit">{labels.submit}</Button>
            <Button variant="secondary" onClick={() => setOpen(false)}>{labels.cancel}</Button>
          </div>
        </form>
      ) : (
        <Button variant="secondary" icon="lock" onClick={() => setOpen(true)} testId="pii-open">{labels.open}</Button>
      )}
    </div>
  );
}
