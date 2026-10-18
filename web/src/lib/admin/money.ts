// Admin money display: the same formatters as the reports and the dashboard (lib/reports/format.ts), so one amount reads the same
// everywhere (Western grouping, F-SYS-051). Kept as named exports for the admin pages.
import type { Locale } from "@/lib/i18n";
import { formatTaka, formatTakaDisplay } from "@/lib/reports/format";

export type Mtk = number | bigint | string;

/** Milli-taka (integer) as taka with all three decimals (nothing rounded): what a money approver signs off. */
export const formatMtkExact = (locale: Locale, mtk: Mtk): string => formatTaka(locale, mtk);

/** Milli-taka (integer) as taka with two decimals (half up on the magnitude), BigInt maths; digits in the UI language. */
export const formatMtk = (locale: Locale, mtk: Mtk): string => formatTakaDisplay(locale, mtk);
