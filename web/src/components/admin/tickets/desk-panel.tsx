"use client";
// The support desk's actions on one ticket (TK-16, D-TSO-MAN-20, 21): Start, Reply, Resolve, Cancel and the priority while the
// ticket is open; Close once it is resolved; nothing on a closed or cancelled ticket (no reopen). Every update carries `op_uuid`
// and the `expected_version` the page read; a 409 ERR_CONFLICT (someone changed the ticket meanwhile) is shown with a reload, so
// the desk always acts on the ticket as it is now.
import { useState } from "react";
import type { SupportTicketPriority, SupportTicketStatus } from "@/contract/types";
import { useI18n } from "@/components/i18n-provider";
import { Field } from "@/components/admin/kit/field";
import { Banner } from "@/components/kit/banner";
import { Button, INPUT_CLASS } from "@/components/kit/button";
import type { MessageKey } from "@/lib/i18n";
import { CancelForm } from "./cancel-form";
import { ReplyForm } from "./reply-form";
import { ResolveForm, type ResolveCode } from "./resolve-form";
import { deskActions, TICKET_PRIORITIES, updateBody, type DeskAction, type TicketOutcome } from "./ticket-rules";
import { useTicketOp, type SendTicketOp } from "./use-ticket-op";

const DONE: Record<DeskAction, MessageKey> = {
  reply: "tickets.reply.done",
  start: "tickets.start.done",
  resolve: "tickets.resolve.done",
  cancel: "tickets.cancel.done",
  close: "tickets.close.done",
  priority: "tickets.priority.done",
};

/** What the desk is told after an action: done, a conflict to reload, the server's refusal, or no answer. */
export function TicketOpBanner({ action, outcome, onReload }: { action: DeskAction; outcome: TicketOutcome; onReload?: () => void }) {
  const { t, problem } = useI18n();
  if (outcome.kind === "ok") return <Banner tone="success" role="status" testId="op-ok" data-action={action}>{t(DONE[action])}</Banner>;
  if (outcome.kind === "conflict") {
    return (
      <Banner tone="warning" role="alert" testId="op-conflict" data-action={action} action={<Button type="button" variant="secondary" icon="refresh" onClick={onReload} testId="op-reload">{t("tickets.reload")}</Button>}>
        {t("tickets.conflict")}
      </Banner>
    );
  }
  if (outcome.kind === "reloaded") return <Banner tone="warning" role="status" testId="op-reloaded" data-action={action}>{t("tickets.reloaded")}</Banner>;
  if (outcome.kind === "refused") return <Banner tone="danger" role="alert" testId="op-refused" data-action={action} data-code={outcome.code}>{problem(outcome.code)}</Banner>;
  return <Banner tone="danger" role="alert" testId="op-network" data-action={action}>{t("error.network")}</Banner>;
}

function StatusActions({ send, busy, version, status, priority }: { send: SendTicketOp; busy: boolean; version: number; status: SupportTicketStatus; priority: SupportTicketPriority }) {
  const { t } = useI18n();
  const actions = deskActions(status);
  const [next, setNext] = useState<SupportTicketPriority>(priority);
  const [prioError, setPrioError] = useState<string | null>(null);
  const changePriority = async () => {
    if (next === priority) return setPrioError(t("tickets.priority.same"));
    setPrioError(null);
    await send("priority", "ticket.update", (key) => updateBody(version, key, { kind: "priority", priority: next }));
  };
  return (
    <div className="flex flex-wrap items-end gap-4" data-testid="status-actions">
      {actions.includes("start") ? (
        <Button type="button" variant="secondary" icon="clock" disabled={busy} onClick={() => void send("start", "ticket.update", (key) => updateBody(version, key, { kind: "start" }))} testId="start-submit">
          {t("tickets.start.submit")}
        </Button>
      ) : null}
      {actions.includes("close") ? (
        <div className="space-y-2">
          <p className="max-w-prose text-caption text-secondary">{t("tickets.close.hint")}</p>
          <Button type="button" variant="secondary" icon="lock" disabled={busy} onClick={() => void send("close", "ticket.update", (key) => updateBody(version, key, { kind: "close" }))} testId="close-submit">
            {t("tickets.close.submit")}
          </Button>
        </div>
      ) : null}
      {actions.includes("priority") ? (
        <div className="flex flex-wrap items-end gap-2">
          <Field label={t("tickets.priority.label")} htmlFor="tk-priority" error={prioError}>
            <select id="tk-priority" value={next} onChange={(e) => setNext(e.target.value as SupportTicketPriority)} className={INPUT_CLASS}>
              {TICKET_PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {t(`tickets.priority.${p}` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
          <Button type="button" variant="secondary" disabled={busy} onClick={() => void changePriority()} testId="priority-submit">
            {t("tickets.priority.submit")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export interface DeskPanelProps {
  ticketUuid: string;
  version: number;
  status: SupportTicketStatus;
  priority: SupportTicketPriority;
  codes: readonly ResolveCode[];
  codesFallback: boolean;
}

export function DeskPanel({ ticketUuid, version, status, priority, codes, codesFallback }: DeskPanelProps) {
  const { t } = useI18n();
  const op = useTicketOp(ticketUuid);
  const actions = deskActions(status);
  const busy = op.busy !== null;
  return (
    <div className="space-y-5" data-testid="desk-panel" data-version={version}>
      {op.last ? <TicketOpBanner action={op.last.action} outcome={op.last.outcome} onReload={op.reload} /> : null}
      {actions.length === 0 ? <p className="text-body text-secondary" data-testid="desk-none">{t("tickets.desk.none")}</p> : null}
      {/* keyed by the priority: after a refresh the select starts from the ticket's current priority again */}
      {actions.some((a) => a === "start" || a === "close" || a === "priority") ? <StatusActions key={priority} send={op.send} busy={busy} version={version} status={status} priority={priority} /> : null}
      {actions.includes("reply") ? <ReplyForm send={op.send} busy={busy} /> : null}
      {actions.includes("resolve") || actions.includes("cancel") ? (
        <div className="grid gap-6 lg:grid-cols-2">
          {actions.includes("resolve") ? <ResolveForm send={op.send} busy={busy} version={version} codes={codes} codesFallback={codesFallback} /> : null}
          {actions.includes("cancel") ? <CancelForm send={op.send} busy={busy} version={version} /> : null}
        </div>
      ) : null}
    </div>
  );
}
