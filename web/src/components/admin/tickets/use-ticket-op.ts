"use client";
// One desk action through the BFF whitelist (lib/admin/ops.ts "ticket.update" and "ticket.event"). Each action keeps its own
// idempotency key (`op_uuid` or `event_uuid`) only while its last attempt got no answer (ticket-rules keepsKey) AND the request is
// unchanged (`requestSignature`), so a double click or a resend after a lost answer is the same op on the server and is applied
// once, while an edited reply, note, reason or priority is a new op with a new key (never an old key with a new body, which the
// server refuses as 409 ERR_CONFLICT). A 409 ERR_CONFLICT on such a resend means our own first send may have landed: the page is
// read again (`reloaded`) instead of saying someone else changed the ticket. A settled answer starts a fresh key.
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { nextKey, outcomeOf, requestSignature, settleAttempt, type DeskAction, type KeyedAttempt, type TicketOutcome } from "./ticket-rules";

export type TicketOpKey = "ticket.update" | "ticket.event";
export interface TicketOpResult {
  action: DeskAction;
  outcome: TicketOutcome;
}
/** Sends one action; `body` gets the action's idempotency key. Resolves true when the server applied (or replayed) it. */
export type SendTicketOp = (action: DeskAction, op: TicketOpKey, body: (key: string) => object) => Promise<boolean>;

export function useTicketOp(ticketUuid: string) {
  const router = useRouter();
  const attempts = useRef<Partial<Record<DeskAction, KeyedAttempt>>>({});
  const inFlight = useRef(false);
  const [busy, setBusy] = useState<DeskAction | null>(null);
  const [last, setLast] = useState<TicketOpResult | null>(null);

  const send: SendTicketOp = async (action, op, body) => {
    if (inFlight.current) return false; // a second click while the first is on its way does nothing
    inFlight.current = true;
    const signature = requestSignature(body(""));
    const { key, resent } = nextKey(attempts.current[action], signature, () => crypto.randomUUID());
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
    attempts.current[action] = settleAttempt(key, signature, status);
    const outcome = outcomeOf(status, code, resent);
    setLast({ action, outcome });
    setBusy(null);
    inFlight.current = false;
    if (outcome.kind === "ok" || outcome.kind === "reloaded") router.refresh();
    return outcome.kind === "ok";
  };

  /** After a conflict: read the ticket again (new version, new status), then the desk applies its change again. */
  const reload = () => {
    setLast(null);
    router.refresh();
  };

  return { send, busy, last, reload };
}
