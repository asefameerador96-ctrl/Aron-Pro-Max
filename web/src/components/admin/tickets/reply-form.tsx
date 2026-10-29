"use client";
// The desk's reply (D-TSO-MAN-21; contract addSupportTicketEvent): 1 to 2,000 characters, only while the ticket is open. The
// reporter sees it as "Support". Idempotent by `event_uuid` (useTicketOp keeps it until the server answers).
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Field } from "@/components/admin/kit/field";
import { Button, INPUT_CLASS } from "@/components/kit/button";
import { REPLY_MAX, replyProblems, type FieldCode } from "./ticket-rules";
import type { SendTicketOp } from "./use-ticket-op";

export const FIELD_KEY = { required: "error.field.required", too_short: "error.field.too_short", too_long: "error.field.too_long" } as const satisfies Record<FieldCode, string>;

export function ReplyForm({ send, busy }: { send: SendTicketOp; busy: boolean }) {
  const { t } = useI18n();
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const p = replyProblems(body);
    setError(p.body ? t(FIELD_KEY[p.body]) : null);
    if (p.body) return;
    if (await send("reply", "ticket.event", (key) => ({ event_uuid: key, body: body.trim() }))) setBody("");
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-3" data-testid="reply-form">
      <Field label={t("tickets.reply.label")} htmlFor="tk-reply" required hint={t("tickets.reply.hint")} error={error}>
        <textarea id="tk-reply" name="body" rows={3} maxLength={REPLY_MAX} value={body} onChange={(e) => setBody(e.target.value)} aria-invalid={error ? true : undefined} className={`${INPUT_CLASS} py-2`} />
      </Field>
      <Button type="submit" variant="primary" icon="upload" disabled={busy} testId="reply-submit">
        {busy ? t("tickets.busy") : t("tickets.reply.submit")}
      </Button>
    </form>
  );
}
