// REQUEST: docs/requests/backend-admin-user-access.md. The portal's access page calls an operation the contract does not
// carry yet. `it.fails` passes while the contract lacks it and turns RED the day the contract gains it: then delete the
// provisional types in lib/admin/access-grants.ts, regenerate the client and drop this file.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const yaml = readFileSync(resolve(__dirname, "../../../contract/openapi.yaml"), "utf8");

describe("contract carries the user-access operations (not yet: backend-authz / backend-admin)", () => {
  it.fails("GET and PUT /v1/admin/users/{id}/access exist in contract/openapi.yaml", () => {
    expect(yaml).toContain("/v1/admin/users/{id}/access:");
  });
});
