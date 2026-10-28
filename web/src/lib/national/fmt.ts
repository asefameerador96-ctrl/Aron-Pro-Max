// Number and time text of the national dashboard. Quantities in Lakh / Crore with two decimals, Bengali digits in Bangla mode (docs/34 s2).
import { formatLakhCrore } from "@/lib/dash/lakh";
import { formatNumber, t, type Locale } from "@/lib/i18n";

/** 3.64 Cr, 34.88 Lakh, 11,165: the card format of the original portal. Non-integers are rounded half up first. */
export function qty(locale: Locale, n: number): string {
  return formatLakhCrore(locale, Math.round(n), { lakh: t(locale, "unit.lakh"), crore: t(locale, "unit.crore") });
}

/** Percent with `digits` decimals (1 on most cards, 2 on Geo Fencing), "—" when there is nothing to divide by. */
export function pct(locale: Locale, v: number | null | undefined, digits = 1): string {
  return v === null || v === undefined || !Number.isFinite(v) ? "—" : `${formatNumber(locale, v, { minimumFractionDigits: digits, maximumFractionDigits: digits })}%`;
}

/** HH:mm (Dhaka) of an instant, localised digits; "" for a missing or invalid instant. */
export function clock(locale: Locale, iso: string | null, seconds = false): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-GB", { timeZone: "Asia/Dhaka", hour: "2-digit", minute: "2-digit", ...(seconds ? { second: "2-digit" } : {}), hourCycle: "h23" }).format(d);
}

/** Hours between two instants with two decimals (the route map's time card); null when either is missing. */
export function hoursBetween(a: string | null, b: string | null): number | null {
  if (!a || !b) return null;
  const ms = Date.parse(b) - Date.parse(a);
  return Number.isFinite(ms) && ms >= 0 ? Math.round((ms / 3_600_000) * 100) / 100 : null;
}

/** Distance covered: metres with two decimals below 1,000 m, kilometres with one decimal from 1,000 m (docs/34 s6 Q1). */
export function distance(locale: Locale, m: number): string {
  return m < 1000 ? `${formatNumber(locale, m, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${t(locale, "nat.unit.m")}` : `${formatNumber(locale, m / 1000, { minimumFractionDigits: 1, maximumFractionDigits: 1 })} ${t(locale, "nat.unit.km")}`;
}

/** A percentage from integer basis points (0 to 10000 is 0 to 100%), divided only here, at display, with integer arithmetic: 9796 -> 97.96%.
 *  `digits` is 2 on Geo Fencing and the route map, 1 on the other cards, 0 on the route map's bars; half up on the magnitude. */
export function pctBp(locale: Locale, bp: number | null | undefined, digits: 0 | 1 | 2 = 1): string {
  if (bp === null || bp === undefined || !Number.isSafeInteger(bp)) return DASH;
  const unit = 10 ** (2 - digits); // basis points per displayed step
  const steps = Math.floor((Math.abs(bp) + unit / 2) / unit);
  // tso-manual W1: the text is pctText's (Western grouping in both locales, as the phone and the corpus). With digits 2 the bp is
  // already at the printed precision and the figure is exact; with 1 or 0 it is rounded a second time, so a card that has the
  // integers prints pctText(pctTenths(n, d), 1) instead (D-TSO-MAN-10: one rounding per figure).
  return pctText(locale, bp < 0 ? -steps : steps, digits);
}

// ------------------------------------------------------------------------------------------------------------- tso-manual W1
// The TSO manual numbers (D-TSO-MAN-10; build contract docs/requests/tso-manual-build-2026-10.md s4.1 to s4.4): integers in,
// text out, ONE rounding per figure, half up, exactly as shared/rules Percent, Qty, Targets and TicketMath print them on the
// phone. Arithmetic is BigInt inside, so no intermediate loses a digit; an input that is not a non-negative safe integer
// gives null (a dash), never a wrong figure. contract/fixtures/kpi-format-corpus.json (tools/ci/kpi-format-corpus.py, an
// independent integer oracle) pins every function below in en and bn: tests/kpi-format-corpus.test.ts.

/** What a missing figure prints as (no whole to divide by, no target, no working day): U+2014 EM DASH, as the phone. */
export const DASH = "—";
/** The thousands suffix of a quantity, with its leading space; Latin in both locales. */
export const K_SUFFIX = " K";

const BN_DIGITS = "০১২৩৪৫৬৭৮৯";

/** Bengali digits in Bangla, Latin in English; every other character stays ("," "." "%" and "K" are Latin in both). */
export function localDigits(locale: Locale, s: string): string {
  return locale === "bn" ? s.replace(/\d/g, (c) => BN_DIGITS[Number(c)]!) : s;
}

/** A non-negative integer with "," every three digits: Western grouping in both locales (F-SYS-051), never lakh. */
function grouped(locale: Locale, v: bigint): string {
  return localDigits(locale, v.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ","));
}

/** The BigInt of a non-negative safe integer; null for anything else (a missing, negative, fractional or unsafe input). */
function nat(v: number | null | undefined): bigint | null {
  return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? BigInt(v) : null;
}

/** A BigInt result as a number; null when it would not be exact (beyond 2^53, far outside the contract's bounds). */
function exact(v: bigint): number | null {
  return v <= BigInt(Number.MAX_SAFE_INTEGER) && v >= -BigInt(Number.MAX_SAFE_INTEGER) ? Number(v) : null;
}

/** round_half_up(scale * n / d) as (2 * scale * n + d) div (2 * d); null when d = 0 or an input is not a non-negative integer. */
function scaled(n: number | null | undefined, d: number | null | undefined, scale: bigint): number | null {
  const a = nat(n);
  const b = nat(d);
  if (a === null || b === null || b === 0n) return null;
  return exact((2n * scale * a + b) / (2n * b));
}

/** Whole percent of n in d: login and Bikroy Joma bars, Brand CPR, the target card (then capped), tickets. 2 of 3 -> 67. */
export const pctWhole = (n: number | null | undefined, d: number | null | undefined): number | null => scaled(n, d, 100n);
/** Tenths of a percent: dashboard rings and cards, CPR, Geo Fencing. 29 of 1,186 -> 24 ("2.4%", never the 2.5% of 245 bp). */
export const pctTenths = (n: number | null | undefined, d: number | null | undefined): number | null => scaled(n, d, 1_000n);
/** Basis points: target details (2 dp), channel pie (2 dp), what the server sends as `*_bp`. 85 of 81 -> 10494. */
export const pctBpOf = (n: number | null | undefined, d: number | null | undefined): number | null => scaled(n, d, 10_000n);

/** A scaled integer percent as text: v / 10^decimals with exactly `decimals` digits and "%", whole part grouped; null -> "—".
 *  24 tenths -> "2.4%", 10494 bp -> "104.94%", 40 whole -> "৪০%" (bn). Pair it with pctWhole, pctTenths or pctBpOf. */
export function pctText(locale: Locale, v: number | null | undefined, decimals: 0 | 1 | 2): string {
  if (typeof v !== "number" || !Number.isSafeInteger(v)) return DASH;
  const a = BigInt(Math.abs(v));
  const pow = 10n ** BigInt(decimals);
  const frac = decimals === 0 ? "" : `.${localDigits(locale, (a % pow).toString().padStart(decimals, "0"))}`;
  return `${v < 0 ? "-" : ""}${grouped(locale, a / pow)}${frac}%`;
}

/** A quantity n / d (d = 1 for a total, working days for a daily target), one decimal half up: "40.0", "999.0"; from 1,000.0 in
 *  thousands with one decimal and " K", rounded once from n: "1.7 K", "1,234.6 K". No M, no lakh. A null n or d = 0 -> "—". */
export function qtyText(locale: Locale, n: number | null | undefined, d = 1): string {
  const a = nat(n);
  const b = nat(d);
  if (a === null || b === null || b === 0n) return DASH;
  const tenths = (20n * a + b) / (2n * b);
  if (tenths < 10_000n) return localDigits(locale, `${tenths / 10n}.${tenths % 10n}`);
  const k = (2n * a + 100n * b) / (200n * b);
  return `${grouped(locale, k / 10n)}.${localDigits(locale, (k % 10n).toString())}${K_SUFFIX}`;
}

/** Item till-date target round_half_up(t * e / n) with e clamped to 0..n; null when n = 0 (a month without working days). */
export function tillDate(t: number, e: number, n: number): number | null {
  const tt = nat(t);
  const nn = nat(n);
  if (tt === null || nn === null || nn === 0n || !Number.isSafeInteger(e)) return null;
  const ee = BigInt(Math.min(Math.max(e, 0), n));
  return exact((2n * tt * ee + nn) / (2n * nn));
}

/** Remaining target max(t - a, 0): 81 - 85 -> 0. */
export function remaining(t: number, a: number): number {
  return Math.max(t - a, 0);
}

export type Band = "red" | "amber" | "green";
/** `cfg.kpi.bar_bands` (default [40, 80]) on the PRINTED integer percent: below lo red, below hi amber, else green. */
export function band(p: number, lo = 40, hi = 80): Band {
  return p < lo ? "red" : p < hi ? "amber" : "green";
}

/** Target card percent: min(100, whole(a, t)); null when t = 0. 85 of 81 -> 100. */
export function cardPct(a: number, t: number): number | null {
  const p = pctWhole(a, t);
  return p === null ? null : Math.min(p, 100);
}

/** One Target Status card (s4.3): the monthly target is the sum of the items; the till-date target is the sum of the ITEM till-date
 *  rows, each rounded on its own (R-F-01: 33114 at 21/26, never tillDate of the summed 40999 = 33115); capped whole % and band. */
export function targetCard(items: readonly number[], achieved: number, e: number, n: number, lo = 40, hi = 80) {
  const monthlyTarget = items.reduce((s, x) => s + x, 0);
  const rows = items.map((x) => tillDate(x, e, n));
  const tillDateTarget = rows.every((r) => r !== null) ? rows.reduce<number>((s, r) => s + (r ?? 0), 0) : null;
  const monthlyPct = cardPct(achieved, monthlyTarget);
  const tillDatePct = tillDateTarget === null ? null : cardPct(achieved, tillDateTarget);
  return {
    monthlyTarget,
    tillDateTarget,
    monthlyPct,
    tillDatePct,
    monthlyBand: monthlyPct === null ? null : band(monthlyPct, lo, hi),
    tillDateBand: tillDatePct === null ? null : band(tillDatePct, lo, hi),
  };
}

/** Dashboard sales ring: today's sales a against today's target t / w, in tenths of a percent, uncapped: tenths(a * w, t). Null
 *  when w = 0 (R-F-13: no working day, no daily target), when t = 0, or when a or t is missing. */
export function dailyRingTenths(a: number | null | undefined, t: number | null | undefined, w: number): number | null {
  const aa = nat(a);
  const ww = nat(w);
  if (aa === null || ww === null || ww === 0n) return null;
  const aw = exact(aa * ww);
  return aw === null ? null : pctTenths(aw, t);
}

/** The phone's sales card (s4.2) as text: Total Sales qty(a), Total Target qty(t, w), ring tenths(a * w, t) with one decimal.
 *  W = 0 prints "—" for the target and the ring (R-F-13); a missing a or t prints "—" where it is needed. */
export function salesCard(locale: Locale, a: number | null | undefined, t: number | null | undefined, w: number) {
  const ringTenths = dailyRingTenths(a, t, w);
  return { totalSales: qtyText(locale, a), totalTarget: qtyText(locale, t, w), ringTenths, ring: pctText(locale, ringTenths, 1) };
}

/** Ticket counts by status (D-TSO-MAN-25, R-F-14): open = new + in_progress; resolved % and open % are whole percent computed
 *  ONCE from the counts (never from a rounded basis point); 0 when there is no ticket. */
export function ticketCounts(c: { total: number; new: number; in_progress: number; resolved: number }) {
  const open = c.new + c.in_progress;
  return { total: c.total, open, resolvedPct: pctWhole(c.resolved, c.total) ?? 0, openPct: pctWhole(open, c.total) ?? 0 };
}

/** A ticket's age from its capture time and now (D-TSO-MAN-25): under 1 minute "just now", under 1 hour minutes, under 24 hours
 *  hours, else days, each rounded down; a capture after now (clock skew) is "just now". Digits ungrouped, as the phone. */
export function ticketAge(locale: Locale, nowMs: number, capturedMs: number): string {
  const ms = Math.max(0, nowMs - capturedMs);
  if (!Number.isFinite(ms) || ms < 60_000) return t(locale, "tickets.age.just_now");
  const [key, n] = ms < 3_600_000 ? (["tickets.age.m", Math.floor(ms / 60_000)] as const) : ms < 86_400_000 ? (["tickets.age.h", Math.floor(ms / 3_600_000)] as const) : (["tickets.age.d", Math.floor(ms / 86_400_000)] as const);
  return t(locale, key, { n: localDigits(locale, String(n)) });
}
