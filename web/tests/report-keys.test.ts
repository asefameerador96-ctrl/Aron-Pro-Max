import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { REPORT_KEYS } from "@/lib/admin/report-keys";

describe("report keys", () => {
  it("equal the contract's ReportKey enum exactly", () => {
    const yaml = readFileSync("../contract/openapi.yaml", "utf8");
    const line = yaml.split("\n").find((l) => l.trim().startsWith("enum: [std-memo"))!;
    const keys = line.slice(line.indexOf("[") + 1, line.lastIndexOf("]")).split(",").map((k) => k.trim());
    expect([...REPORT_KEYS].sort()).toEqual(keys.sort());
  });
});
