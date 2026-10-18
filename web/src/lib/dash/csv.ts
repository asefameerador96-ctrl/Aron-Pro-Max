// CSV for the dashboard tables: UTF-8 with a byte-order mark (Excel reads Bengali), CRLF, quoted fields, and a leading
// apostrophe on text that a spreadsheet would run as a formula (docs/24 s12.3). Plain numbers, negatives and Bengali-digit
// numbers are left alone.
import type { Locale } from "@/lib/i18n";
import { mtkToTaka } from "@/lib/reports/format";

const NUMBER_LIKE = /^-?[\d০-৯][\d০-৯,.]*%?$/;

export function csvField(v: string): string {
  const safe = /^[=+\-@\t\r]/.test(v) && !NUMBER_LIKE.test(v) ? `'${v}` : v;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function toCsv(header: readonly string[], rows: readonly (readonly string[])[]): string {
  return `﻿${[header, ...rows].map((r) => r.map(csvField).join(",")).join("\r\n")}\r\n`;
}

const BENGALI = "০১২৩৪৫৬৭৮৯";
/** Digits for a CSV cell: Bengali in bn, Latin in en, no grouping (a spreadsheet must still read the number). */
export function csvDigits(locale: Locale, s: string): string {
  return locale === "bn" ? s.replace(/\d/g, (d) => BENGALI[Number(d)]!) : s;
}

/** Money in taka with three decimals from integer milli-taka (BigInt maths in `mtkToTaka`), no grouping. */
export const csvTaka = (locale: Locale, mtk: number): string => csvDigits(locale, mtkToTaka(mtk));

export const csvInt = (locale: Locale, v: number): string => csvDigits(locale, String(v));

/** A percentage with one decimal, or empty when there is no denominator. */
export const csvPct = (locale: Locale, v: number | null | undefined): string => (v === null || v === undefined ? "" : csvDigits(locale, v.toFixed(1)));
