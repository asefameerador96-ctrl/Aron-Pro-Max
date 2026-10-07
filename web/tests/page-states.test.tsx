// Every dashboard route group ships loading, error and not-found states, and long Bangla text does not break tiles.
import { existsSync, readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "bn" }));

describe("page states", () => {
  it("loading, error and not-found exist for the dashboards group", () => {
    for (const f of ["loading", "error", "not-found"]) expect(existsSync(`src/app/(dashboards)/${f}.tsx`), f).toBe(true);
  });
  it("loading is an announced status with Bangla text", async () => {
    const Loading = (await import("@/app/(dashboards)/loading")).default;
    const html = renderToStaticMarkup(await Loading());
    expect(html).toContain('role="status"');
    expect(html).toMatch(/[ঀ-৿]/);
  });
  it("tiles wrap long text anywhere instead of overflowing", () => {
    expect(readFileSync("src/components/dash/tiles.tsx", "utf8")).toContain("overflow-wrap:anywhere");
  });
  it("the chart uses the token colour, not a hard-coded one", () => {
    const src = readFileSync("src/components/reports/bar-chart.tsx", "utf8");
    expect(src).toContain("var(--chart-1)");
    expect(src).not.toMatch(/#[0-9a-f]{6}/i);
  });
});
