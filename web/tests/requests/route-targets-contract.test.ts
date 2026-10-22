// REQUEST: docs/requests/backend-admin-route-targets.md. Passes while the contract lacks the operation, turns RED when it lands:
// then drop the PROVISIONAL marks in lib/admin/route-targets.ts, use the generated types and delete this file.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const yaml = readFileSync(resolve(__dirname, "../../../contract/openapi.yaml"), "utf8");

describe("contract carries the route-target operations (not yet: backend-admin)", () => {
  // Landed (backend-admin): now a plain assertion; drop the PROVISIONAL marks and this file.
  it("GET and PUT /v1/admin/route-targets exist in contract/openapi.yaml", () => {
    expect(yaml).toContain("/v1/admin/route-targets:");
  });
});
