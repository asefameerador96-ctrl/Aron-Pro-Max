// Working days of a month for the calendar page (D-TARGET-MIN-01: the daily target divides the monthly one by the working days;
// Fridays and holidays are excluded). The SERVER counts them (contract getWorkingDays, national entries only); this file only
// reads its answer into the three numbers of the table.
import type { Schemas } from "@/contract/types";

export interface MonthDays {
  month: string;
  working: number;
  weekend: number;
  /** National days off that fall on a weekday (holiday or emergency off). */
  holidays: number;
}

/** Table row of a server month: weekend and holiday counts are the days that are NOT working (a make-up Friday counts as working). */
export function monthDaysOf(w: Schemas["WorkingDays"]): MonthDays {
  const off = w.days.filter((d) => !d.is_working_day);
  return { month: w.month, working: w.working_days, weekend: off.filter((d) => d.is_weekend).length, holidays: off.filter((d) => !d.is_weekend).length };
}

export const daysInMonth = (month: string): number => new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate();
