// REQUEST: docs/requests/backend-admin-outlet-import.md. Passes while the contract lacks the operation, turns RED when it lands:
// then drop the PROVISIONAL marks in lib/admin/outlet-import.ts, use the generated types and delete this file.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const yaml = readFileSync(resolve(__dirname, "../../../contract/openapi.yaml"), "utf8");

describe("contract carries the outlet import (not yet: backend-admin)", () => {
  it("POST /v1/admin/outlets/import exists in contract/openapi.yaml", () => {
    expect(yaml).toContain("/v1/admin/outlets/import:");
  });
});
