import { describe, expect, it } from "vitest";
import { daysInMonth, isoWeekday, workingDays } from "@/lib/admin/calendar-days";

describe("workingDays", () => {
  it("counts a 30-day month with Fridays off (November 2026 has four Fridays: 6, 13, 20, 27)", () => {
    expect(isoWeekday("2026-11-06")).toBe(5);
    expect(daysInMonth("2026-11")).toBe(30);
    expect(workingDays("2026-11", [5], [])).toEqual({ month: "2026-11", working: 26, weekend: 4, holidays: 0 });
  });
  it("removes a national holiday on a weekday, ignores one on a weekend, adds a make-up day on a Friday", () => {
    const hol = [
      { date: "2026-11-02", scope_type: "global" as const, selling_day: false }, // Monday
      { date: "2026-11-06", scope_type: "global" as const, selling_day: false }, // Friday: already off
      { date: "2026-11-13", scope_type: "global" as const, selling_day: true }, // make-up Friday
    ];
    expect(workingDays("2026-11", [5], hol)).toEqual({ month: "2026-11", working: 26, weekend: 3, holidays: 1 });
  });
  it("a zone-specific day off does not change the national count; other months' entries are ignored", () => {
    expect(workingDays("2026-11", [5], [{ date: "2026-11-03", scope_type: "zone", selling_day: false }, { date: "2026-12-03", scope_type: "global", selling_day: false }]).working).toBe(26);
  });
  it("handles February of a leap year and two weekend days", () => {
    expect(daysInMonth("2028-02")).toBe(29);
    expect(workingDays("2028-02", [5, 6], []).working).toBe(29 - 8); // Feb 2028 has four Fridays and four Saturdays
  });
});
