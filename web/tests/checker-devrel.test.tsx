// Checker (F-ADM-009/048/078/027/049): refutation tests. Each failing test names a defect.
import { describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { ReleasesView } from "@/components/admin/config/releases-view";
import { DeviceReplaceView } from "@/components/admin/config/device-replace-view";
import { parseConfigInput } from "@/lib/admin/config";
import type { Device } from "@/lib/admin/types";

// minimal hook runtime so the client form can be driven without a DOM
const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0 }));
vi.mock("react", async (orig: () => Promise<Record<string, unknown>>) => {
  const real = await orig();
  return {
    ...real,
    useState: (init: unknown) => {
      const k = hooks.i++;
      if (!(k in hooks.cells)) hooks.cells[k] = typeof init === "function" ? (init as () => unknown)() : init;
      return [hooks.cells[k], (v: unknown) => void (hooks.cells[k] = typeof v === "function" ? (v as (p: unknown) => unknown)(hooks.cells[k]) : v)];
    },
  };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
vi.mock("@/components/i18n-provider", () => ({ useI18n: () => ({ t: (k: string) => k, problem: (c: string) => String(c) }) }));

function walk(n: unknown, f: (e: { type?: unknown; props: Record<string, unknown> }) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (!n || typeof n !== "object") return;
  const e = n as { type?: unknown; props?: Record<string, unknown> };
  if (e.props) {
    f({ type: e.type, props: e.props });
    walk(e.props.children, f);
  }
}
/** Drive OpForm: type values, submit, return the JSON sent to the BFF. */
async function drive(Comp: (p: never) => ReactElement, props: object, values: Record<string, string>, reason = "A sufficiently long reason") {
  hooks.cells = []; hooks.i = 0;
  const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: {} }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const render = () => { hooks.i = 0; return (Comp as (p: object) => ReactElement)(props); };
  for (const [name, v] of Object.entries(values)) {
    let tree = render();
    walk(tree, (e) => { if (e.props.name === name && typeof e.props.onChange === "function") (e.props.onChange as (x: unknown) => void)({ target: { value: v, checked: false } }); });
    tree = render();
  }
  let tree = render();
  walk(tree, (e) => { if (e.props.value !== undefined && typeof e.props.onChange === "function" && e.props.name === undefined) (e.props.onChange as (x: unknown) => void)(reason); });
  tree = render();
  let submit: ((e: unknown) => Promise<void>) | undefined;
  walk(tree, (e) => { if (e.type === "form") submit = e.props.onSubmit as typeof submit; });
  await submit!({ preventDefault() {} });
  const call = fetchMock.mock.calls[0] as unknown as [string, { body: string }] | undefined;
  return call ? (JSON.parse(call[1].body) as { body: Record<string, unknown> }) : null;
}

const dev = { device_id: 7, status: "active", bound_users: [], pending_rows_reported: 3 } as unknown as Device;

describe("checker F-ADM-078 replace wizard", () => {
  it("a non-integer new-phone user id must not be silently rounded to another user (OTP for the wrong rep)", async () => {
    let p: object = {};
    walk((DeviceReplaceView as unknown as (x: object) => ReactElement)({ locale: "en", device: dev }), (e) => { if (e.props.op === "device.replace") p = e.props; });
    const { OpForm } = await import("@/components/admin/kit/op-form");
    const sent = await drive(OpForm as never, p, { new_device_user_id: "12.5" });
    // either blocked locally (null) or not sent as 13
    expect(sent === null || sent.body.new_device_user_id !== 13, JSON.stringify(sent)).toBe(true);
  });
});

describe("checker releases policy keys", () => {
  it("min_version_code / blocked_version_codes edits are shape-checked ({sr,amo,tso} of ints / int lists) before sending", () => {
    for (const bad of ['{"sr":"abc","amo":1,"tso":1}', "[1,2,3]", "{}", '{"sr":1.5,"amo":1,"tso":1}', '{"sr":-4,"amo":1,"tso":1}']) {
      expect(parseConfigInput("json", bad, null, "cfg.release.min_version_code").ok, bad).toBe(false);
    }
    for (const bad of ['{"sr":[1,"x"],"amo":[],"tso":[]}', '{"sr":5}', "[1]"]) {
      expect(parseConfigInput("json", bad, null, "cfg.release.blocked_version_codes").ok, bad).toBe(false);
    }
  });
});

describe("checker release registration gates", () => {
  const props = { locale: "en" as const, releases: [], policy: [], adoption: [], policyKeys: [], policyValues: {}, canWrite: true, canPublish: true };
  const sha = "a".repeat(64);
  const good = { flavour: "sr", version_name: "1.2.0", version_code: "12", abi: "arm64-v8a", sha256: sha, signing_cert_sha256: sha, size_bytes: "1000", download_url: "https://x.example/a.apk" };
  const run = async (over: Record<string, string>, apkMaxMb = 30) => {
    // ReleasesView renders OpForm as an element; drive OpForm itself with the fields ReleasesView built
    let fields: unknown[] = [];
    walk((ReleasesView as unknown as (p: object) => ReactElement)({ ...props, apkMaxMb }), (e) => { if (Array.isArray(e.props.fields) && e.props.op === "release.create") fields = e.props.fields; });
    const { OpForm } = await import("@/components/admin/kit/op-form");
    return drive(OpForm as never, { op: "release.create", fields, noReason: true, submitLabel: "x" }, { ...good, ...over });
  };
  it("accepts a good registration (control)", async () => {
    expect((await run({}))?.body).toMatchObject({ size_bytes: 1000, version_code: 12 });
  });
  it("rejects size_bytes 0 and version_code 0 locally (contract minimum 1)", async () => {
    expect(await run({ size_bytes: "0" })).toBeNull();
    expect(await run({ version_code: "0" })).toBeNull();
  });
  it("rejects version_code above 2100000000 and a fractional size locally", async () => {
    expect(await run({ version_code: "2100000001" })).toBeNull();
    expect(await run({ size_bytes: "1000.6" })).toBeNull();
  });
  it("never lets the size gate exceed the contract maximum of 100 MB even if the config says more", async () => {
    expect(await run({ size_bytes: String(150 * 1024 * 1024) }, 200)).toBeNull();
  });
  it("accepts an upper-case or whitespace-padded checksum by normalising to lower case (keytool prints upper case)", async () => {
    expect((await run({ sha256: sha.toUpperCase() }))?.body.sha256).toBe(sha);
  });
});
