import { describe, expect, it } from "vitest";
import { POST as assetPost } from "@/app/api/bff/admin/assets/route";
import { POST as tutorialPost } from "@/app/api/bff/admin/tutorials/route";
import { PATCH as tutorialPatch } from "@/app/api/bff/admin/tutorials/[id]/route";
import { blobOrigin, csp } from "@/lib/security-headers";
import { checkAssetRequest, checkTutorialWrite } from "@/lib/admin/tutorials";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason that is long enough";
const ASSET = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const SHA = "a".repeat(64);
const asset = (b: Record<string, unknown> = {}) => ({ asset_id: ASSET, purpose: "tutorial_video", mime: "video/mp4", bytes: 1000, sha256: SHA, ...b });
const write = (b: Record<string, unknown> = {}) => ({ kind: "video", title_en: "New tutorial", title_bn: null, asset_id: ASSET, roles: ["SR"], sort: 3, change_reason: REASON, ...b });
const upload = async (c: Record<string, string>, bytes = 1000) => {
  const r = await assetPost(req("/api/bff/admin/assets", "POST", asset({ bytes }), c));
  const url = ((await r.json()) as { data: { upload_url: string } }).data.upload_url;
  expect((await fetch(url, { method: "PUT", body: new Uint8Array(bytes) })).status).toBe(201);
};

describe("tutorial checks (F-ADM-026)", () => {
  it("asset request: type must match the purpose, size 1..100 MB, lower-case hash and v4 uuid", () => {
    expect(checkAssetRequest(asset()).body).toBeTruthy();
    for (const bad of [{ mime: "application/pdf" }, { purpose: "sku_image" }, { bytes: 0 }, { bytes: 104_857_601 }, { bytes: 1.5 }, { sha256: SHA.toUpperCase() }, { asset_id: ASSET.toUpperCase() }, { extra: 1 }]) expect(checkAssetRequest(asset(bad)).body, JSON.stringify(bad)).toBeUndefined();
  });
  it("tutorial write: roles non-empty and unique, reason 10 to 500 code points, no unknown members", () => {
    expect(checkTutorialWrite(write()).body).toBeTruthy();
    for (const bad of [{ roles: [] }, { roles: ["SR", "SR"] }, { roles: ["BOSS"] }, { kind: "audio" }, { title_en: "  " }, { sort: -1 }, { sort: 1.5 }, { status: "gone" }, { change_reason: "short" }, { extra: 1 }]) expect(checkTutorialWrite(write(bad)).body, JSON.stringify(bad)).toBeUndefined();
  });
  it("the CSP names the blob origin only when ARON_BLOB_ORIGIN is one clean origin", () => {
    expect(blobOrigin("https://acct.blob.core.windows.net")).toBe("https://acct.blob.core.windows.net");
    for (const bad of ["http://evil.example", "https://*.example.com", "https://a.example/path", "javascript:alert(1)", "not a url", ""]) expect(blobOrigin(bad), bad).toBeNull();
    expect(csp("n")).not.toContain("blob.core");
  });
});

describe("tutorial BFF", () => {
  it("uploads through a SAS URL, then creates the tutorial with a reason and an audit row", async () => {
    const c = await signIn("madmin1");
    await upload(c);
    const res = await tutorialPost(req("/api/bff/admin/tutorials", "POST", write(), c));
    expect(res.status).toBe(201);
    expect(mock.state.tables.tutorials!.some((r) => r.title_en === "New tutorial")).toBe(true);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "tutorial", reason: REASON });
  });
  it("refuses a bad write with 400 and nothing changes; SUPPORT and TSO get 403", async () => {
    const c = await signIn("madmin1");
    const before = mock.state.tables.tutorials!.length;
    expect((await tutorialPost(req("/api/bff/admin/tutorials", "POST", write({ roles: [] }), c))).status).toBe(400);
    expect((await tutorialPost(req("/api/bff/admin/tutorials", "POST", write({ change_reason: "no" }), c))).status).toBe(400);
    expect(mock.state.tables.tutorials!.length).toBe(before);
    for (const u of ["msupport1", "mtso1"] as const) {
      const s = await signIn(u);
      expect((await tutorialPost(req("/api/bff/admin/tutorials", "POST", write(), s))).status).toBe(403);
      expect((await assetPost(req("/api/bff/admin/assets", "POST", asset(), s))).status).toBe(403);
    }
  });
  it("update sends If-Match from the version: a stale version is 412; a bad id or version is 400", async () => {
    const c = await signIn("madmin1");
    await upload(c);
    const patch = (id: string, b: Record<string, unknown>) => tutorialPatch(req(`/api/bff/admin/tutorials/${id}`, "PATCH", b, c), { params: Promise.resolve({ id }) });
    expect((await patch("1", { ...write({ title_en: "Renamed" }), version: 1 })).status).toBe(200);
    expect((await patch("1", { ...write({ title_en: "Again" }), version: 1 })).status).toBe(412);
    expect((await patch("x", { ...write(), version: 1 })).status).toBe(400);
    expect((await patch("1", write())).status).toBe(400);
    expect((await patch("1", { ...write(), version: 0 })).status).toBe(400);
  });
});
