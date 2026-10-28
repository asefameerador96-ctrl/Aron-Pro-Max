"use client";
// Cancel (D-TSO-MAN-20): a reason of 3 to 500 characters (a duplicate, a test ticket), asked again before sending because a
// cancelled ticket is never opened again. Sent as one op with `op_uuid` and the `expected_version` the desk read.
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Field } from "@/components/admin/kit/field";
import { Button, INPUT_CLASS } from "@/components/kit/button";
import { CANCEL_MAX, cancelProblems, updateBody } from "./ticket-rules";
import { FIELD_KEY } from "./reply-form";
import type { SendTicketOp } from "./use-ticket-op";

export function CancelForm({ send, busy, version }: { send: SendTicketOp; busy: boolean; version: number }) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const p = cancelProblems(reason);
    setError(p.cancel_reason ? t(FIELD_KEY[p.cancel_reason]) : null);
    if (p.cancel_reason) return;
    if (!window.confirm(t("tickets.cancel.confirm"))) return;
    if (await send("cancel", "ticket.update", (key) => updateBody(version, key, { kind: "cancel", reason }))) setReason("");
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-3" data-testid="cancel-form">
      <h3 className="text-heading text-primary">{t("tickets.cancel.title")}</h3>
      <Field label={t("tickets.cancel.reason")} htmlFor="tk-cancel-reason" required hint={t("tickets.cancel.hint")} error={error}>
        <textarea id="tk-cancel-reason" name="cancel_reason" rows={2} maxLength={CANCEL_MAX} value={reason} onChange={(e) => setReason(e.target.value)} aria-invalid={error ? true : undefined} className={`${INPUT_CLASS} py-2`} />
      </Field>
      <Button type="submit" variant="secondary" icon="close" disabled={busy} testId="cancel-submit">
        {busy ? t("tickets.busy") : t("tickets.cancel.submit")}
      </Button>
    </form>
  );
}
