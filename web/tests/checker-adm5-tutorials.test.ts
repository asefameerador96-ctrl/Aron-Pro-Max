// CHECKER (independent, T2) for F-ADM-026: tutorial content management (BFF, validation, CSP origin).
import { describe, expect, it } from "vitest";
import { POST as assetPost } from "@/app/api/bff/admin/assets/route";
import { POST as tutorialPost } from "@/app/api/bff/admin/tutorials/route";
import { PATCH as tutorialPatch } from "@/app/api/bff/admin/tutorials/[id]/route";
import { blobOrigin, csp } from "@/lib/security-headers";
import { checkTutorialWrite } from "@/lib/admin/tutorials";
import { NextRequest } from "next/server";
import { ORIGIN, req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason that is long enough";
const ASSET = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const write = (b: Record<string, unknown> = {}) => ({ kind: "video", title_en: "New tutorial", title_bn: null, asset_id: ASSET, roles: ["SR"], sort: 3, change_reason: REASON, ...b });
const asset = { asset_id: ASSET, purpose: "tutorial_video", mime: "video/mp4", bytes: 1000, sha256: "a".repeat(64) };
const cookie = (c: Record<string, string>) => Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ");

describe("CSP and ARON_BLOB_ORIGIN", () => {
  it("a value that smuggles a CSP directive with ';' (no space needed for sandbox) is refused", () => {
    for (const bad of ["https://a.example;sandbox", "https://a.example;upgrade-insecure-requests", "https://a.example,x"]) {
      expect(blobOrigin(bad), bad).toBeNull();
    }
  });
  it("csp() never emits an extra directive from the env value", () => {
    const old = process.env.ARON_BLOB_ORIGIN;
    process.env.ARON_BLOB_ORIGIN = "https://a.example;sandbox";
    try {
      const directives = csp("n").split("; ").map((d) => d.split(" ")[0]);
      expect(directives).not.toContain("sandbox");
      expect(csp("n")).not.toContain(";sandbox");
    } finally {
      if (old === undefined) delete process.env.ARON_BLOB_ORIGIN;
      else process.env.ARON_BLOB_ORIGIN = old;
    }
  });
});

describe("tutorial write validation", () => {
  it("title lengths count code points (120 emoji and 120 Bangla code points pass, 121 fail)", () => {
    expect(checkTutorialWrite(write({ title_en: "😀".repeat(120) })).body).toBeTruthy();
    expect(checkTutorialWrite(write({ title_en: "😀".repeat(121) })).body).toBeUndefined();
    expect(checkTutorialWrite(write({ title_bn: "ক্ষ".repeat(40) })).body).toBeTruthy();
    expect(checkTutorialWrite(write({ title_bn: "ক্ষ".repeat(41) })).body).toBeUndefined();
  });
  it("an empty Bangla title is sent as null, like the form does (the contract's title_bn is nullable)", () => {
    expect(checkTutorialWrite(write({ title_bn: "   " })).body?.title_bn).toBeNull();
  });
  it("a padded short reason is refused", () => {
    expect(checkTutorialWrite(write({ change_reason: "   short    " })).body).toBeUndefined();
  });
});

describe("tutorial BFF guards", () => {
  it("anonymous 401; cross-site 403 even for ADMIN; no upstream call", async () => {
    expect((await tutorialPost(req("/api/bff/admin/tutorials", "POST", write()))).status).toBe(401);
    const c = await signIn("madmin1");
    const before = mock.state.tables.tutorials!.length;
    const cross = new NextRequest(`${ORIGIN}/api/bff/admin/tutorials`, {
      method: "POST", body: JSON.stringify(write()),
      headers: { "content-type": "application/json", host: "localhost:3000", "sec-fetch-site": "cross-site", cookie: cookie(c) },
    });
    expect((await tutorialPost(cross)).status).toBe(403);
    const evil = new NextRequest(`${ORIGIN}/api/bff/admin/assets`, {
      method: "POST", body: JSON.stringify(asset),
      headers: { "content-type": "application/json", host: "localhost:3000", origin: "https://evil.example", cookie: cookie(c) },
    });
    expect((await assetPost(evil)).status).toBe(403);
    expect(mock.state.tables.tutorials!.length).toBe(before);
  });
  it("SUPPORT may not PATCH either (read-only), and nothing changes", async () => {
    const s = await signIn("msupport1");
    const row = mock.state.tables.tutorials![0]!;
    const res = await tutorialPatch(req("/api/bff/admin/tutorials/1", "PATCH", { ...write({ title_en: "Hacked" }), version: row.version }, s), { params: Promise.resolve({ id: "1" }) });
    expect(res.status).toBe(403);
    expect(row.title_en).not.toBe("Hacked");
  });
  it("a body that is an array is 400", async () => {
    const c = await signIn("madmin1");
    expect((await tutorialPost(req("/api/bff/admin/tutorials", "POST", [write()], c))).status).toBe(400);
  });
  it("the asset ticket is not echoed into the audit log", async () => {
    const c = await signIn("madmin1");
    const res = await assetPost(req("/api/bff/admin/assets", "POST", asset, c));
    const url = ((await res.json()) as { data: { upload_url: string } }).data.upload_url;
    expect(JSON.stringify(mock.state.audit)).not.toContain(url);
  });
});
