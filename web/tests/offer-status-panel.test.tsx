// The status panel states (portal-discount-web): the past-valid_to activation block and the Super-Admin-only note.
import { describe, expect, it, vi } from "vitest";
import { OfferStatusPanel } from "@/components/admin/offer-status-panel";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => undefined, push: () => undefined }) }));

describe("offer status panel", () => {
  it("a draft whose end date is in the past explains why it cannot be activated and the button is disabled", () => {
    const out = html(<OfferStatusPanel offerId={1} moves={["active"]} pastEnd superOnlyNote={false} />, "en");
    expect(out).toContain('data-testid="os-past"');
    expect(text(out)).toContain("The end date is in the past, so it cannot be activated");
    expect(/<button[^>]*data-testid="os-active"[^>]*>/.exec(out)?.[0]).toContain('aria-disabled="true"');
  });
  it("an end date today or later shows no block and an enabled button", () => {
    const out = html(<OfferStatusPanel offerId={1} moves={["active"]} pastEnd={false} superOnlyNote={false} />, "en");
    expect(out).not.toContain('data-testid="os-past"');
    expect(/<button[^>]*data-testid="os-active"[^>]*>/.exec(out)?.[0]).not.toContain('aria-disabled="true"'); // (the class list names aria-disabled: variants)
  });
  it("an ADMIN looking at a draft sees the Super-Admin-only note and no activate button", () => {
    const out = html(<OfferStatusPanel offerId={1} moves={[]} pastEnd={false} superOnlyNote />, "en");
    expect(text(out)).toContain("Only a Super Admin can activate a draft");
    expect(out).not.toContain('data-testid="os-active"');
  });
  it("an active programme offers only the end move, in Bangla too; with nothing to show the panel is absent", () => {
    const bn = html(<OfferStatusPanel offerId={1} moves={["inactive"]} pastEnd superOnlyNote={false} />, "bn");
    expect(bn).toContain('data-testid="os-inactive"');
    expect(bn).not.toContain('data-testid="os-active"');
    expect(html(<OfferStatusPanel offerId={1} moves={[]} pastEnd={false} superOnlyNote={false} />, "en")).toBe("");
  });
});
