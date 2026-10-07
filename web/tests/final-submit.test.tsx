// F-WEB-051 Web Final Submit.
import { describe, expect, it, vi } from "vitest";
import { FinalSubmitView, type FinalSubmitViewProps } from "@/components/admin/config/final-submit-view";
import { formatMtk } from "@/lib/admin/money";
import type { FinalSubmitPreview } from "@/lib/admin/types";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const route = (id: number, over: Partial<FinalSubmitPreview["routes"][number]> = {}) => ({ route_id: id, route_code: `R-${id}`, route_name: `Route ${id}`, kind: "sr" as const, state: "sales_submitted" as const, had_data: true, memo_count: 12, net_mtk: 1_234_567, ff_user_id: 5, ff_user_name: "Rahim", pending_rows_reported: 0, ...over });
const preview = (over: Partial<FinalSubmitPreview> = {}): FinalSubmitPreview => ({ zone_id: 334, business_date: "2026-10-06", already_submitted: false, routes: [route(1), route(2, { state: "in_field", pending_rows_reported: 3, ff_user_id: null, ff_user_name: null })], warnings: ["routes_not_sales_submitted"], submitted_at: null, submitted_by: null, ...over });
const base: FinalSubmitViewProps = { locale: "en", options: { wing: [], division: [], territory: [], house: [], zone: [] }, selection: { zone: "334" }, date: "2026-10-06", today: "2026-10-06", preview: preview(), canSubmit: true, canVoid: true };

describe("money", () => {
  it("formats milli-taka with integer arithmetic", () => {
    expect(formatMtk("en", 1_234_567)).toBe("1,234.57");
    expect(formatMtk("en", 999)).toBe("1.00");
    expect(formatMtk("en", -5_000)).toBe("-5.00");
    expect(formatMtk("en", 0)).toBe("0.00");
    expect(formatMtk("bn", 1_234_567)).toBe("১,২৩৪.৫৭");
  });
});

describe("FinalSubmitView", () => {
  it("lists routes with state, memos and net sales; SR Not Set for a route without a rep", () => {
    const m = text(html(<FinalSubmitView {...base} />));
    expect(m).toContain("R-1 · Route 1");
    expect(m).toContain("1,234.57");
    expect(m).toContain("SR Not Set");
    expect(m).toContain("In the field");
  });
  it("shows the DSS advisory with the server warnings, and Delete Section Data per route with data, and the submit form", () => {
    const m = html(<FinalSubmitView {...base} />);
    expect(m).toContain('data-testid="dss-advisory"');
    expect(text(m)).toContain("Some routes have not done Sales Submit");
    expect(m).toContain("void-1");
    expect(m).toContain("void-2");
    expect(m).toContain('data-testid="final-submit-form"');
  });
  it("shows the back-date banner only for an earlier day", () => {
    expect(html(<FinalSubmitView {...base} />)).not.toContain("backdate-banner");
    expect(html(<FinalSubmitView {...base} date="2026-10-04" />)).toContain("backdate-banner");
  });
  it("after Final Submit: alert, no submit, no delete", () => {
    const m = html(<FinalSubmitView {...base} preview={preview({ already_submitted: true, submitted_by: "Karim", submitted_at: "2026-10-06T12:00:00.000Z" })} />);
    expect(m).toContain("already-submitted");
    expect(text(m)).toContain("Karim");
    expect(m).not.toContain("final-submit-form");
    expect(m).not.toContain("void-1");
  });
  it("a role without the right gets neither action; routes without data cannot be voided", () => {
    const m = html(<FinalSubmitView {...base} canSubmit={false} canVoid={false} />);
    expect(m).not.toContain("final-submit-form");
    expect(m).not.toContain("void-1");
    expect(html(<FinalSubmitView {...base} preview={preview({ routes: [route(3, { had_data: false })] })} />)).not.toContain("void-3");
  });
  it("asks for a zone first", () => {
    expect(text(html(<FinalSubmitView {...base} preview={null} selection={{}} />))).toContain("Choose a zone");
  });
  it("Bangla labels", () => {
    expect(text(html(<FinalSubmitView {...base} locale="bn" />))).toContain("ফাইনাল সাবমিট");
  });
});
