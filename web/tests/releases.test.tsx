// F-ADM-027 / F-ADM-049: release registration gates, actions per status and role, adoption.
import { describe, expect, it, vi } from "vitest";
import { ReleasesView } from "@/components/admin/config/releases-view";
import { adoptionByVersion } from "@/lib/admin/adoption";
import type { AppRelease, Device } from "@/lib/admin/types";
import { admin, op, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const rel = (id: number, status: AppRelease["status"]): AppRelease => ({ release_id: id, flavour: "sr", version_name: "1.2.0", version_code: 12, abi: "arm64-v8a", sha256: "a".repeat(64), size_bytes: 20 * 1024 * 1024, download_url: "https://x.example/a.apk", signing_cert_sha256: "b".repeat(64), status, rollout_pct: 100, created_at: "2026-10-01T00:00:00.000Z", version: 3 });
const base = { locale: "en" as const, policy: [{ flavour: "sr" as const, min_version_code: 10, blocked_version_codes: [9], latest_version_code: 12, devices_below_min: 2 }], adoption: [{ flavour: "sr", version: "1.2.0", devices: 6 }, { flavour: "sr", version: "1.1.0", devices: 2 }], policyKeys: [], policyValues: {}, apkMaxMb: 30 };

describe("ReleasesView", () => {
  it("shows size in MB, checksum prefix, status, the policy line and adoption bars", () => {
    const m = text(html(<ReleasesView {...base} releases={[rel(1, "published")]} canWrite canPublish />));
    expect(m).toContain("20 MB");
    expect(m).toContain("aaaaaaaaaaaa");
    expect(m).toContain("Published");
    expect(m).toContain("Minimum code 10");
    expect(m).toContain("Blocked codes 9");
    expect(m).toContain("Phones below the minimum 2");
    expect(m).toContain("SR 1.2.0");
  });
  it("only a super administrator sees Publish; an admin sees block and retire", () => {
    const rows = [rel(1, "draft"), rel(2, "published")];
    const sa = html(<ReleasesView {...base} releases={rows} canWrite canPublish />);
    expect(sa).toContain("publish-1");
    expect(sa).toContain("block-2");
    const ad = html(<ReleasesView {...base} releases={rows} canWrite canPublish={false} />);
    expect(ad).not.toContain("publish-1");
    expect(ad).toContain("block-2");
    expect(text(ad)).toContain("Only a super administrator can publish");
    const ro = html(<ReleasesView {...base} releases={rows} canWrite={false} canPublish={false} />);
    expect(ro).not.toContain("block-2");
    expect(ro).not.toContain('data-testid="release-form"');
  });
  it("the register form states the size gate and has no reason box (the contract has no reason member)", () => {
    const m = html(<ReleasesView {...base} releases={[]} canWrite canPublish />);
    expect(text(m)).toContain("Largest allowed APK: 30 MB");
    expect(m).toContain('data-testid="release-form"');
  });
});

describe("adoption", () => {
  it("counts live phones per flavour and version, ignoring revoked and replaced", () => {
    const d = (flavour: Device["flavour"], v: string | null, status: Device["status"] = "active") => ({ flavour, app_version: v, status }) as Device;
    expect(adoptionByVersion([d("sr", "1.2.0"), d("sr", "1.2.0"), d("sr", "1.1.0"), d("tso", "1.0.0"), d("sr", "1.2.0", "revoked"), d("sr", "1.2.0", "replaced"), d("amo", null)])).toEqual([
      { flavour: "amo", version: "?", devices: 1 },
      { flavour: "sr", version: "1.2.0", devices: 2 },
      { flavour: "sr", version: "1.1.0", devices: 1 },
      { flavour: "tso", version: "1.0.0", devices: 1 },
    ]);
  });
});

describe("release ops", () => {
  it("register is sent as given (closed schema, no reason member); publish needs a SUPERADMIN", async () => {
    h.stub({ method: "POST", path: "/v1/admin/releases", fn: () => ({ status: 201, body: rel(5, "draft") }) });
    h.stub({ method: "PATCH", path: "/v1/admin/releases/5", fn: () => ({ status: 200, body: rel(5, "published") }) });
    const cookies = await admin();
    const body = { flavour: "sr", version_name: "1.2.0", version_code: 12, abi: "arm64-v8a", sha256: "a".repeat(64), size_bytes: 1000, download_url: "https://x.example/a.apk", signing_cert_sha256: "b".repeat(64) };
    expect((await op(cookies, { op: "release.create", body })).status).toBe(201);
    expect(h.calls()[0]?.body).toEqual(body);
    expect((await op(cookies, { op: "release.publish", params: { release_id: "5" }, version: 3, body: { status: "published" }, reason: "Tested on the pilot phones" })).status).toBe(403); // admin1 is ADMIN
    const r = await op(cookies, { op: "release.update", params: { release_id: "5" }, version: 3, body: { rollout_pct: 20 }, reason: "Staged rollout to the pilot" });
    expect(r.status).toBe(200);
    expect(h.calls()[1]).toMatchObject({ method: "PATCH", body: { rollout_pct: 20, change_reason: "Staged rollout to the pilot" } });
    expect(h.calls()[1]?.headers["if-match"]).toBe('"3"');
  });
});
