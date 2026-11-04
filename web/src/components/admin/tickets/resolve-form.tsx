"use client";
// Resolve (D-TSO-MAN-20): a resolution code from `ticket_resolution_code` AND notes of 1 to 2,000 characters; the server writes the
// reporter's message "[TicketID: <no>] - Resolved: ... Resolution Notes: <notes>" (screenshot 14). No code is preselected, so the
// desk chooses one on purpose. Sent as one op with `op_uuid` and the `expected_version` the desk read.
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Field } from "@/components/admin/kit/field";
import { Banner } from "@/components/kit/banner";
import { Button, INPUT_CLASS } from "@/components/kit/button";
import { NOTES_MAX, resolveProblems, updateBody } from "./ticket-rules";
import { FIELD_KEY } from "./reply-form";
import type { SendTicketOp } from "./use-ticket-op";

export interface ResolveCode {
  code: string;
  label: string;
}

export function ResolveForm({ send, busy, version, codes, codesFallback }: { send: SendTicketOp; busy: boolean; version: number; codes: readonly ResolveCode[]; codesFallback: boolean }) {
  const { t } = useI18n();
  const [code, setCode] = useState("");
  const [notes, setNotes] = useState("");
  const [errors, setErrors] = useState<{ resolution_code?: string; resolution_notes?: string }>({});

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const p = resolveProblems(code, notes);
    setErrors({ resolution_code: p.resolution_code && t(FIELD_KEY[p.resolution_code]), resolution_notes: p.resolution_notes && t(FIELD_KEY[p.resolution_notes]) });
    if (p.resolution_code || p.resolution_notes) return;
    if (await send("resolve", "ticket.update", (key) => updateBody(version, key, { kind: "resolve", code, notes }))) {
      setCode("");
      setNotes("");
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-3" data-testid="resolve-form">
      <h3 className="text-heading text-primary">{t("tickets.resolve.title")}</h3>
      {codesFallback ? (
        <Banner tone="info" role="note" testId="codes-fallback">
          {t("tickets.resolve.codes_fallback")}
        </Banner>
      ) : null}
      <Field label={t("tickets.resolve.code")} htmlFor="tk-resolve-code" required error={errors.resolution_code}>
        <select id="tk-resolve-code" name="resolution_code" value={code} onChange={(e) => setCode(e.target.value)} aria-invalid={errors.resolution_code ? true : undefined} className={INPUT_CLASS}>
          <option value="">{t("tickets.resolve.choose")}</option>
          {codes.map((c) => (
            <option key={c.code} value={c.code}>
              {c.label}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("tickets.resolve.notes")} htmlFor="tk-resolve-notes" required hint={t("tickets.resolve.hint")} error={errors.resolution_notes}>
        <textarea id="tk-resolve-notes" name="resolution_notes" rows={4} maxLength={NOTES_MAX} value={notes} onChange={(e) => setNotes(e.target.value)} aria-invalid={errors.resolution_notes ? true : undefined} className={`${INPUT_CLASS} py-2`} />
      </Field>
      <Button type="submit" variant="primary" icon="check-circle" disabled={busy} testId="resolve-submit">
        {busy ? t("tickets.busy") : t("tickets.resolve.submit")}
      </Button>
    </form>
  );
}
