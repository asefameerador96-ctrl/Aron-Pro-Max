// CHECKER (independent, T2) for F-ADM-026: the tutorials page and list rendering.
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactElement } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const hooks = vi.hoisted(() => ({ role: "SUPPORT" as string }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", user: { role: hooks.role } }) }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "bn" }));
const raw = vi.hoisted(() => ({ rawRequest: vi.fn() }));
vi.mock("@/lib/api/raw", () => ({ rawRequest: raw.rawRequest }));

import TutorialsPage from "@/app/admin/tutorials/page";
import { TutorialManager } from "@/components/admin/tutorial-manager";
import { I18nProvider } from "@/components/i18n-provider";

const items = [{ tutorial_id: 1, kind: "video" as const, title_en: "How to sell", title_bn: "বিক্রি", sort: 1, roles: ["SR", "TSO"], status: "active" as const, version: 1, bytes: 10 }, { tutorial_id: 2, kind: "manual" as const, title_en: "Manual", title_bn: null, sort: 2, roles: ["AMO"], status: "inactive" as const, version: 1, bytes: 10 }];
const under = (el: ReactElement, locale: "en" | "bn" = "bn") => renderToStaticMarkup(<I18nProvider locale={locale}>{el}</I18nProvider>);
beforeEach(() => { vi.resetAllMocks(); hooks.role = "SUPPORT"; raw.rawRequest.mockResolvedValue({ ok: true, status: 200, data: { items } }); });

describe("tutorials page", () => {
  it("TSO sees Forbidden and the API is not called", async () => {
    hooks.role = "TSO";
    await TutorialsPage();
    expect(raw.rawRequest).not.toHaveBeenCalled();
  });
  it("SUPPORT reads the list without the form or the edit buttons", async () => {
    const html = renderToStaticMarkup(await TutorialsPage());
    expect(html).toContain("How to sell");
    expect(html).not.toContain("tutorial-submit");
    expect(html).not.toContain("edit-1");
  });
});

describe("tutorial list rendering", () => {
  it("shows the role names localised, as the form does (not raw codes like 'SR, TSO')", () => {
    const html = under(<TutorialManager items={items} canWrite={false} />);
    expect(html).not.toContain(">SR, TSO<");
  });
  it("each Edit button names its row for screen readers (WCAG 2.4.4)", () => {
    const html = under(<TutorialManager items={items} canWrite />, "en");
    const m = [...html.matchAll(/<button[^>]*data-testid="edit-\d+"[^>]*>/g)].map((x) => x[0]);
    expect(m).toHaveLength(2);
    for (const b of m) expect(b).toMatch(/aria-label=/);
  });
  it("the list table has an accessible name or caption", () => {
    const html = under(<TutorialManager items={items} canWrite />, "en");
    expect(/<caption|<table[^>]*aria-label/.test(html)).toBe(true);
  });
});
