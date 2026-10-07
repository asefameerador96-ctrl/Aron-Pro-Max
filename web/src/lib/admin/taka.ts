/** Taka text ("1250", "1,250.5", "12.05") to integer milli-taka without floating point; null when it is not a plain amount with at most 3 decimals. */
export function takaToMtk(text: string): number | null {
  const m = /^(-?)(\d{1,12})(?:\.(\d{1,3}))?$/.exec(latinDigits(text).trim().replace(/,/g, ""));
  if (!m) return null;
  const whole = Number(m[2]) * 1000;
  const frac = Number((m[3] ?? "").padEnd(3, "0"));
  const v = whole + frac;
  if (!Number.isSafeInteger(v)) return null;
  return m[1] === "-" && v !== 0 ? -v : v;
}

const BN = "০১২৩৪৫৬৭৮৯";
/** Bengali digits to Latin (a Bangla keyboard types them). */
export const latinDigits = (text: string): string => text.replace(/[০-৯]/g, (d) => String(BN.indexOf(d)));

/** Exact decimal text times a power of ten scale (1000 = three decimals) as a safe integer; null when finer than the scale or out of range. */
export function scaledInt(text: string, scale: number): number | null {
  const decimals = Math.round(Math.log10(scale));
  const m = new RegExp(`^(-?)(\\d{1,15})(?:\\.(\\d{1,${decimals}}))?$`).exec(latinDigits(text).trim().replace(/,/g, ""));
  if (!m) return null;
  const v = Number(m[2]) * scale + Number((m[3] ?? "").padEnd(decimals, "0") || "0");
  if (!Number.isSafeInteger(v)) return null;
  return m[1] === "-" && v !== 0 ? -v : v;
}

/** Only the digits of typed text, Bengali digits converted (a quantity box must not swallow what a Bangla keyboard types). */
export const digitsOnly = (text: string): string => latinDigits(text).replace(/\D/g, "");
