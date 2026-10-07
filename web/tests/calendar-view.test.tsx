// F-ADM-033: the calendar page model. Weekend days and holidays are shown; the declare form and the weekend form need the write role.
import { describe, expect, it, vi } from "vitest";
import { CalendarView } from "@/components/admin/config/calendar-view";
import type { ConfigKey, Holiday } from "@/lib/admin/types";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const holiday = (over: Partial<Holiday>): Holiday => ({ id: 1, date: "2026-10-20", scope_type: "global", scope_id: 0, kind: "holiday", selling_day: false, name_en: "Eid", name_bn: "ঈদ", ...over });
const key: ConfigKey = { key: "cfg.calendar.weekend_days", area: "calendar", kind: "S", value_type: "list", default_value: [5], bounds: { max_items: 3, min: 1, max: 7 }, scope_levels: ["global"], risk_class: 3, effect: "S", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.calendar", description_en: "Weekend" };
const props = { holidays: [holiday({}), holiday({ id: 2, date: "2026-10-25", kind: "makeup_day", selling_day: true, name_en: "Make-up", name_bn: null })], weekend: { key: key.key, value: [5], scope_type: "default" as const, effective_from: null, requires_ack: false }, weekendKey: key, from: "2026-10-01", to: "2026-12-23" };

describe("CalendarView", () => {
  it("shows the weekend days, holidays with kind, scope and selling flag", () => {
    const m = text(html(<CalendarView locale="en" {...props} canWrite />));
    expect(m).toContain("Friday");
    expect(m).toContain("Eid");
    expect(m).toContain("Make-up working day");
    expect(m).toContain("Selling");
  });
  it("shows Bangla names and Bangla digits in bn", () => {
    const m = text(html(<CalendarView locale="bn" {...props} canWrite />));
    expect(m).toContain("ঈদ");
    expect(m).toContain("শুক্রবার");
    expect(m).toMatch(/২০২৬/);
  });
  it("hides both forms from a read-only role", () => {
    const m = html(<CalendarView locale="en" {...props} canWrite={false} />);
    expect(m).not.toContain('data-testid="holiday-form"');
    expect(m).not.toContain('data-testid="weekend-form"');
  });
  it("write role gets the declare form with a mandatory reason and the weekend form for the registry key", () => {
    const m = html(<CalendarView locale="en" {...props} canWrite />);
    expect(m).toContain('data-testid="holiday-form"');
    expect(m).toContain('data-key="cfg.calendar.weekend_days"');
    expect(m).toContain('name="reason"');
  });
});
