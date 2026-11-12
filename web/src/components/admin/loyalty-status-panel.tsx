"use client";
// Status change of a loyalty programme (portal-loyalty-web): draft -> active (Super Admin) and active -> inactive ("Ended"), each with
// a mandatory reason of 10 to 500 characters and one request_uuid per submission, re-sent on a retry of the same change.
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import { failureKey, nextAttempt, REASON_MIN } from "@/lib/admin/loyalty";
import { ReasonField } from "./kit/reason-field";

export function LoyaltyStatusPanel({ programmeId, moves, pastEnd, superOnlyNote }: { programmeId: number; moves: ("active" | "inactive")[]; pastEnd: boolean; superOnlyNote: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [open, setOpen] = useState<"active" | "inactive" | null>(null);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ tone: "danger" | "success"; text: string } | null>(null);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!open) return;
    const len = Array.from(reason.trim()).length;
    if (len < REASON_MIN || len > 500) {
      setReasonError(t(len < REASON_MIN ? "loy.err.reason_short" : "loy.err.reason_long"));
      return;
    }
    setReasonError(null);
    const key = `${open}:${reason.trim()}`;
    attempt.current = nextAttempt(attempt.current, key);
    setBusy(true);
    setBanner(null);
    try {
      const res = await fetch(`/api/bff/admin/loyalty/${programmeId}/status`, { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ request_uuid: attempt.current.uuid, to_status: open, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        attempt.current = null;
        setBanner({ tone: "success", text: t(open === "active" ? "loy.status.done_active" : "loy.status.done_inactive") });
        setOpen(null);
        setReason("");
        router.refresh();
        return;
      }
      const key = failureKey(res.status, data.code, undefined, "status");
      setBanner({ tone: "danger", text: key ? t(key) : problem(data.code) });
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  if (moves.length === 0 && !superOnlyNote) return null;
  return (
    <section className="glass-card space-y-3 p-(--card-pad)" aria-labelledby="ls-h" data-testid="loyalty-status-panel">
      <h2 id="ls-h" className="text-heading text-primary">{t("loy.status.heading")}</h2>
      {banner ? <Banner tone={banner.tone} role={banner.tone === "danger" ? "alert" : "status"} testId="loyalty-status-banner">{banner.text}</Banner> : null}
      {superOnlyNote ? <p className="text-body text-secondary" data-testid="ls-super-only">{t("loy.status.super_only")}</p> : null}
      {moves.includes("active") && pastEnd ? <p role="note" className="text-body text-secondary" data-testid="ls-past">{t("loy.status.past")}</p> : null}
      {open === null ? (
        <div className="flex flex-wrap gap-3">
          {moves.map((m) => (
            <Button key={m} type="button" variant={m === "active" ? "primary" : "secondary"} aria-disabled={m === "active" && pastEnd ? true : undefined} onClick={() => { if (!(m === "active" && pastEnd)) setOpen(m); }} testId={`ls-${m}`}>
              {t(m === "active" ? "loy.status.activate" : "loy.status.end")}
            </Button>
          ))}
        </div>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-3" data-testid="ls-form">
          <p className="text-caption text-secondary">{t("loy.status.field_note")}</p>
          <ReasonField id="ls-reason" value={reason} onChange={setReason} error={reasonError} />
          <div className="flex flex-wrap gap-3">
            <Button type="submit" variant={open === "inactive" ? "destructive" : "primary"} aria-disabled={busy || undefined} testId="ls-confirm">{busy ? t("loy.form.saving") : t(open === "active" ? "loy.status.confirm_activate" : "loy.status.confirm_end")}</Button>
            <Button type="button" variant="text" onClick={() => { setOpen(null); setReasonError(null); }}>{t("loy.status.cancel")}</Button>
          </div>
        </form>
      )}
    </section>
  );
}
