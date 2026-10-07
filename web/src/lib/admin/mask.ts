// PII masking for payloads shown on review pages (docs/21 s4: raw GPS, phones and personal names are not for admin/support;
// the quarantine review never needs them). Masking is by key (also for everything nested under a PII key) and by content
// (phone numbers inside free text, in Latin and Bengali digits).
export const MASK = "••••";

/** Members whose value is personal: people, contact data, identity documents, address, coordinates, radio environment. */
const PII_KEY = /(^|_)(owner|person|full_?name|first_?name|last_?name|contact|phone|mobile|msisdn|email|nid|tin|licen[cs]e|address|dob|birth|lat|lng|lon|latitude|longitude|altitude|ssid|bssid|wifi|cell)(_|$|[A-Z])|(^|_)(altitude_m)$/i;
const PHONE_BN = /(?:\+?[8৮][8৮])?[0০][1১][3-9৩-৯][0-9০-৯]{8}/g;
const DIGITS_BN = /[০-৯]{10,13}/g;
const SCRUB = /(?:\+?88)?01[3-9]\d{8}/g;

function scrub(s: string): string {
  return s.replace(SCRUB, MASK).replace(PHONE_BN, MASK).replace(DIGITS_BN, MASK);
}

/** A deep copy with PII masked; uuids, ids, quantities, money and accuracy stay readable. */
export function maskPayload(v: unknown, key = "", inPii = false): unknown {
  const pii = inPii || PII_KEY.test(key);
  if (Array.isArray(v)) return v.map((x) => maskPayload(x, key, pii));
  if (v !== null && typeof v === "object") return Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, maskPayload(x, k, pii)]));
  if (pii && (typeof v === "string" || typeof v === "number")) return v === "" ? v : MASK;
  if (typeof v === "string") return scrub(v);
  return v;
}

/** True when a value still carries a mask marker (a masked copy must never be saved as a fix). */
export function containsMask(v: unknown): boolean {
  return JSON.stringify(v ?? null).includes(MASK);
}
