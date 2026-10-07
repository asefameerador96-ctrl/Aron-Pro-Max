// Per-operation consistency rules the proxy applies before calling the API (the API validates again).
import { containsMask } from "./mask";
import type { OpKey } from "./ops";

export type FieldProblem = { pointer: string; code: string };

export function opRules(op: OpKey, body: Record<string, unknown>): FieldProblem[] {
  if (op === "quarantine.resolve") {
    const out: FieldProblem[] = [];
    const fix = body.fixed_record;
    const has = fix !== undefined && fix !== null;
    if (body.action === "accept_with_fix" && !has) out.push({ pointer: "/fixed_record", code: "required" });
    if (body.action !== "accept_with_fix" && has) out.push({ pointer: "/fixed_record", code: "invalid" });
    if (has && (typeof fix !== "object" || Array.isArray(fix) || containsMask(fix))) out.push({ pointer: "/fixed_record", code: "invalid" });
    return out;
  }
  return [];
}
