"use client";
// One desk action through the BFF whitelist (lib/admin/ops.ts "ticket.update" and "ticket.event"). Each action keeps its own
// idempotency key (`op_uuid` or `event_uuid`) until the server has answered (ticket-rules keepsKey), so a double click or a retry
// after a lost answer is the same op on the server and is applied once; a settled answer starts a fresh key for the next action.
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { keepsKey, outcomeOf, type DeskAction, type TicketOutcome } from "./ticket-rules";

export type TicketOpKey = "ticket.update" | "ticket.event";
export interface TicketOpResult {
  action: DeskAction;
  outcome: TicketOutcome;
}
/** Sends one action; `body` gets the action's idempotency key. Resolves true when the server applied (or replayed) it. */
export type SendTicketOp = (action: DeskAction, op: TicketOpKey, body: (key: string) => object) => Promise<boolean>;

export function useTicketOp(ticketUuid: string) {
  const router = useRouter();
  const keys = useRef<Partial<Record<DeskAction, string>>>({});
  const inFlight = useRef(false);
  const [busy, setBusy] = useState<DeskAction | null>(null);
  const [last, setLast] = useState<TicketOpResult | null>(null);

  const send: SendTicketOp = async (action, op, body) => {
    if (inFlight.current) return false; // a second click while the first is on its way does nothing
    inFlight.current = true;
    const key = (keys.current[action] ??= crypto.randomUUID());
    setBusy(action);
    setLast(null);
    let status: number | null = null;
    let code: string | undefined;
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op, params: { ticket_uuid: ticketUuid }, body: body(key) }),
      });
      status = res.status;
      const data = (await res.json().catch(() => ({}))) as { code?: unknown };
      code = typeof data.code === "string" ? data.code : undefined;
    } catch {
      status = null;
    }
    if (!keepsKey(status)) delete keys.current[action];
    const outcome = outcomeOf(status, code);
    setLast({ action, outcome });
    setBusy(null);
    inFlight.current = false;
    if (outcome.kind === "ok") router.refresh();
    return outcome.kind === "ok";
  };

  /** After a conflict: read the ticket again (new version, new status), then the desk applies its change again. */
  const reload = () => {
    setLast(null);
    router.refresh();
  };

  return { send, busy, last, reload };
}
