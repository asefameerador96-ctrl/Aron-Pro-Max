// Checker: the proxy takes the body from the browser, so the ADMIN-only release.update can carry status "published".
import { describe, expect, it } from "vitest";
import { admin, op, setupMock } from "./helpers/harness";

const h = setupMock();
describe("checker maker-checker bypass through the proxy", () => {
  it("ADMIN cannot publish a release by calling release.update with status published (SUPERADMIN only, docs/24 s8.5/8.6)", async () => {
    h.stub({ method: "PATCH", path: "/v1/admin/releases/5", fn: () => ({ status: 200, body: {} }) });
    const r = await op(await admin(), { op: "release.update", params: { release_id: "5" }, version: 3, body: { status: "published" }, reason: "Publishing without a checker" });
    expect(r.status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });
});
