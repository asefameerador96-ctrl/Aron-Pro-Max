// CHECKER v14 (T1 money): what the dues and data-entry screens render (static markup, real i18n).
import { describe, expect, it, vi } from "vitest";
import { DataEntryView } from "@/components/admin/config/data-entry-view";
import { DuesView } from "@/components/admin/config/dues-view";
import type { DuesAdjustment } from "@/lib/admin/types";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const row = (id: number, amount: number, over: Partial<DuesAdjustment> = {}): DuesAdjustment => ({ adjustment_id: id, outlet_id: 55, memo_client_uuid: null, kind: "correction", amount_mtk: amount, reason: "Disputed due after a return", status: "pending", created_by_user_id: 3001, created_at: "2026-10-14T04:00:00Z", decided_by_user_id: null, decided_at: null, ...over });
const view = (rows: DuesAdjustment[], userId = 3003, locale: "en" | "bn" = "en") => text(html(<DuesView locale={locale} rows={rows} status="" outlet="" nextHref={null} userId={userId} canCreate canDecide />, locale));

describe("dues table: the approver sees the exact stored amount", () => {
  it("the form takes three decimals, so the table must show them: 1.005 Tk is not 1.01, -0.004 Tk is not 0.00", () => {
    const out = view([row(1, 1005), row(2, -4)]);
    expect(out).toContain("1.005");
    expect(out).toContain("-0.004");
  });
  it("huge values and Bengali digits render without float drift", () => {
    expect(view([row(1, -10_000_000_000_000)])).toContain("-10,000,000,000.00");
    expect(view([row(1, -1_234_500)], 3003, "bn")).toMatch(/-১,২৩৪\.৫/);
  });
  it("own proposal: no approve/reject for the maker, buttons for another SUPERADMIN", () => {
    const own = html(<DuesView locale="en" rows={[row(7, -1000, { created_by_user_id: 3003 })]} status="" outlet="" nextHref={null} userId={3003} canCreate canDecide />);
    expect(own).not.toContain("dues-approve-7");
    const other = html(<DuesView locale="en" rows={[row(7, -1000)]} status="" outlet="" nextHref={null} userId={3003} canCreate canDecide />);
    expect(other).toContain("dues-approve-7");
  });
});

describe("paper backfill form offers only what the API stores", () => {
  it("no promotion / free line kinds (deferred programmes, docs/27); the API refuses anything but `sale`", () => {
    const out = html(<DataEntryView locale="en" canBackfill />);
    for (const k of ["drp_reward", "promo_free", "free_sample"]) expect(out, k).not.toContain(`value="${k}"`);
  });
  it("hidden when canBackfill is false (flag off)", () => {
    expect(html(<DataEntryView locale="en" canBackfill={false} />)).not.toContain("backfill-form");
  });
});

describe("i18n: every new key in both languages, Bangla actually Bangla", () => {
  it("field codes, dues and paper keys", () => {
    const keys = ["error.field.window", "error.field.zero", "error.field.above_max", "error.field.write_off_must_be_negative", "error.field.not_an_outlet_memo", "error.ERR_SEPARATION_OF_DUTIES", "error.ERR_REQUEST_STATE", "error.ERR_CONFLICT", "de.status.created", "de.status.replayed", "de.status.duplicate_memo_no", "dues.col.decided", "dues.memo", "dues.memo.hint", "dues.own"] as const;
    for (const k of keys) {
      expect((en as Record<string, string>)[k], k).toBeTruthy();
      expect((bn as Record<string, string>)[k], k).toMatch(/[ঀ-৿]/);
    }
  });
});
