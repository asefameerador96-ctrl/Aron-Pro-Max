import { describe, expect, it } from "vitest";
import { daysInMonth, monthDaysOf } from "@/lib/admin/calendar-days";
import type { Schemas } from "@/contract/types";

const day = (date: string, o: Partial<Schemas["WorkingDay"]> = {}): Schemas["WorkingDay"] => ({ date, weekday: 1, is_friday: false, is_weekend: false, is_holiday: false, name_en: null, name_bn: null, is_working_day: true, ...o });

describe("monthDaysOf (server getWorkingDays)", () => {
  it("counts non-working days as weekend or holiday; a make-up weekend day is working", () => {
    const w: Schemas["WorkingDays"] = {
      month: "2026-11", working_days: 3, weekend_days: [5],
      days: [day("2026-11-01"), day("2026-11-02", { is_holiday: true, is_working_day: false }), day("2026-11-06", { is_weekend: true, is_friday: true, is_working_day: false }), day("2026-11-13", { is_weekend: true, is_friday: true }), day("2026-11-14")],
    };
    expect(monthDaysOf(w)).toEqual({ month: "2026-11", working: 3, weekend: 1, holidays: 1 });
  });
  it("daysInMonth knows leap years", () => {
    expect(daysInMonth("2028-02")).toBe(29);
    expect(daysInMonth("2026-11")).toBe(30);
  });
});
