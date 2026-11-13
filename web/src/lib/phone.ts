// Phone repair, the same rule as the server's ImportRules (Kotlin): the field stores phones in many shapes (Bengali digits,
// +880, 00880, a missing leading zero, spaces and dashes). The repaired value is an 11-digit Bangladeshi mobile number
// 01[3-9]XXXXXXXX; anything else is unusable (value null). A blank input is no phone (value null, not unusable).

const ALLOWED_PUNCT = /^[+ \-().]$/;

function toAscii(raw: string): string {
  let out = "";
  for (const ch of raw) {
    const c = ch.codePointAt(0)!;
    if (c >= 0x09e6 && c <= 0x09ef) out += String(c - 0x09e6); // Bengali
    else if (c >= 0x06f0 && c <= 0x06f9) out += String(c - 0x06f0); // Persian
    else if (c >= 0x0660 && c <= 0x0669) out += String(c - 0x0660); // Arabic-Indic
    else out += ch;
  }
  return out;
}

export function normalisePhone(raw: string): { value: string | null; repaired: boolean; unusable: boolean } {
  const trimmed = raw.trim();
  if (trimmed === "") return { value: null, repaired: false, unusable: false };
  const ascii = toAscii(trimmed);
  let digits = "";
  for (const ch of ascii) {
    if (ch >= "0" && ch <= "9") digits += ch;
    else if (!ALLOWED_PUNCT.test(ch)) return { value: null, repaired: false, unusable: true };
  }
  if (digits.startsWith("00880")) digits = digits.slice(2);
  if (digits.startsWith("880") && digits.length === 13) digits = digits.slice(2);
  else if (digits.startsWith("8800") && digits.length === 14) digits = digits.slice(3);
  if (digits.length === 10 && digits.startsWith("1")) digits = "0" + digits;
  if (!/^01[3-9]\d{8}$/.test(digits)) return { value: null, repaired: false, unusable: true };
  return { value: digits, repaired: digits !== trimmed, unusable: false };
}
