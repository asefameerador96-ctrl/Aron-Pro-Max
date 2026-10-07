// Checker (independent) for F-ADM-013/041/043: expected to FAIL where the portal has a gap.
import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { ConfigConsoleView } from "@/components/admin/config/config-console-view";
import { parseConfigInput } from "@/lib/admin/config";
import type { ConfigKey } from "@/lib/admin/types";
import { setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
setupMock();

const sw: ConfigKey = { key: "cfg.ops.read_only_mode", area: "ops", kind: "O", value_type: "bool", default_value: false, bounds: {}, scope_levels: ["global"], risk_class: 3, effect: "B", delivery: "srv", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.ops", description_en: "Read only", description_bn: "শুধু পড়া" };
const base = { locale: "en" as const, basePath: "/admin/config/keys", titleKey: "cfgk.title" as const, keys: [sw], areas: ["ops"], area: undefined, values: {}, reach: null, canWrite: true };

describe("checker F-ADM-041", () => {
  it("a kind O switch edited from the all-keys console (F-ADM-013 page) still demands a duration", () => {
    expect(text(html(<ConfigConsoleView {...base} />))).toContain("Duration (hours)");
  });
});

describe("checker parse", () => {
  it("an enormous integer is not turned into Infinity (JSON null would remove the override)", () => {
    const r = parseConfigInput("int", "9".repeat(400), null);
    expect(r.ok && typeof r.value === "number" ? Number.isFinite(r.value) : true).toBe(true);
  });
  it("an enormous number is not turned into Infinity", () => {
    const r = parseConfigInput("number", "9".repeat(400), null);
    expect(r.ok && typeof r.value === "number" ? Number.isFinite(r.value) : true).toBe(true);
  });
  it("an integer beyond 2^53 is refused rather than silently rounded", () => {
    const r = parseConfigInput("int", "9007199254740993", null);
    expect(r.ok && typeof r.value === "number" ? Number.isSafeInteger(r.value) : true).toBe(true);
  });
});

describe("checker F-ADM-043", () => {
  it("history page can list versions per key and scope (GET /v1/admin/config/values include_history)", () => {
    const src = readFileSync("src/app/admin/config/history/page.tsx", "utf8") + readFileSync("src/components/admin/config/history-view.tsx", "utf8");
    expect(src).toMatch(/config\/values|include_history/);
  });
});
