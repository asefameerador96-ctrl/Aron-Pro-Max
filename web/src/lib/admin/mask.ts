// PII masking for payloads shown on review pages (docs/21: PII only where a role needs it; the quarantine review never needs it).
const PII_KEY = /(phone|mobile|contact|owner|nid|tin|licen[cs]e|address|email|name|dob|birth)/i;

function maskString(s: string): string {
  if (/^\+?[0-9][0-9\s-]{5,}$/.test(s)) return `••••${s.slice(-2)}`;
  return "••••";
}

/** A deep copy with PII-looking members masked; geography ids, uuids, quantities and money stay readable. */
export function maskPayload(v: unknown, key = ""): unknown {
  if (Array.isArray(v)) return v.map((x) => maskPayload(x, key));
  if (v !== null && typeof v === "object") return Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, maskPayload(x, k)]));
  if (typeof v === "string" && PII_KEY.test(key) && v !== "") return maskString(v);
  if (typeof v === "number" && PII_KEY.test(key) && /(phone|mobile|contact|nid|tin)/i.test(key)) return "••••";
  return v;
}
