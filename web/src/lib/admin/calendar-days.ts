// Working days of a month for the target upload page and the calendar (D-TARGET-MIN-01: the daily target divides the monthly one
// by the working days; Fridays and holidays are excluded). National entries only: a day off for one zone or territory does not
// change the national count. Weekday numbers are ISO (1 Monday .. 7 Sunday) like cfg.calendar.weekend_days.
import type { Holiday } from "./types";

export const isoWeekday = (ymd: string): number => {
  const d = new Date(`${ymd}T00:00:00Z`).getUTCDay();
  return d === 0 ? 7 : d;
};
export const daysInMonth = (month: string): number => new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate();

export interface MonthDays {
  month: string;
  working: number;
  weekend: number;
  /** National days off that fall on a weekday (holiday or emergency off). */
  holidays: number;
}

export function workingDays(month: string, weekendDays: readonly number[], holidays: readonly Pick<Holiday, "date" | "scope_type" | "selling_day">[]): MonthDays {
  const national = new Map<string, boolean>();
  for (const h of holidays) if (h.scope_type === "global" && h.date.startsWith(month)) national.set(h.date, h.selling_day);
  let working = 0;
  let weekend = 0;
  let off = 0;
  for (let i = 1; i <= daysInMonth(month); i++) {
    const date = `${month}-${String(i).padStart(2, "0")}`;
    const isWeekend = weekendDays.includes(isoWeekday(date));
    const override = national.get(date);
    const sells = override ?? !isWeekend;
    if (sells) working++;
    else if (isWeekend) weekend++;
    else off++;
  }
  return { month, working, weekend, holidays: off };
}
