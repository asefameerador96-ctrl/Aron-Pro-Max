// tso-manual W1 (build contract s5 W1 "Quarantine page: label and help for after_final_submit"; D-TSO-MAN-31): a row captured before
// the zone-day's Final Submit that arrived after it is parked as `after_final_submit`. The review page names it "Late sale after
// Final Submit", explains what each decision does, and offers the accept decision as "Accept as late sale"; other reasons keep
// their wording. The page runs against the contract mock with the list stubbed.
import { describe, expect, it, vi } from "vitest";
import { QuarantinePageContent } from "@/components/admin/config/quarantine-page";
import { quarantineCodeLabel } from "@/components/admin/config/quarantine-view";
import type { QuarantineItem } from "@/lib/admin/types";
import { admin, setupMock, token } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), redirect: () => { throw new Error("redirect"); } }));
const session = vi.hoisted(() => ({ current: null as null | { user: { role: string }; at: string } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => locale.v }));
const h = setupMock();

const memo = { type: "memo", client_uuid: "44444444-4444-4444-8444-444444444444", payload: { memo_no: "sr334001-261028-031", lines: [] } };
const item = (id: number, code: string): QuarantineItem => ({ quarantine_id: id, client_uuid: memo.client_uuid, type: "memo", code, status: "open", user_id: 1001, business_date: "2026-10-28", received_at: "2026-10-29T03:00:00.000Z", payload: memo } as unknown as QuarantineItem);

async function page(selected: number, lang: "en" | "bn" = "en") {
  locale.v = lang;
  session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
  h.stub({ method: "GET", path: "/v1/admin/quarantine", fn: () => ({ status: 200, body: { items: [item(1, "after_final_submit"), item(2, "unknown_outlet")], next_cursor: null } }) });
  return html(await QuarantinePageContent({ searchParams: Promise.resolve({ item: String(selected) }), basePath: "/admin/quarantine" }), lang);
}

describe("after_final_submit on the review page", () => {
  it("the reason column names the late sale; other codes stay as the server sends them", () => {
    expect(quarantineCodeLabel("en", "after_final_submit")).toBe("Late sale after Final Submit");
    expect(quarantineCodeLabel("bn", "after_final_submit")).toBe("ফাইনাল সাবমিটের পরে আসা বিক্রি");
    expect(quarantineCodeLabel("en", "unknown_outlet")).toBe("unknown_outlet");
  });
  it("a late sale shows the help and the decision 'Accept as late sale'", async () => {
    const m = await page(1);
    expect(m).toContain('data-testid="late-sale-help"');
    const t = text(m).replace(/&#x27;/g, "'");
    expect(t).toContain("Late sale after Final Submit");
    expect(t).toContain("kept out of the day's figures until you decide");
    expect(t).toContain("Accept as late sale");
    expect(t).not.toContain("Accept as it is");
  });
  it("another reason has no late-sale help and keeps 'Accept as it is'", async () => {
    const m = await page(2);
    expect(m).not.toContain("late-sale-help");
    expect(text(m)).toContain("Accept as it is");
  });
  it("bn: the label, the help and the decision in Bangla", async () => {
    const t = text(await page(1, "bn"));
    expect(t).toContain("দেরিতে আসা বিক্রি হিসেবে গ্রহণ");
    expect(t).toContain("এখানে অপেক্ষার সময় কিছুই হারায় না।");
  });
});
