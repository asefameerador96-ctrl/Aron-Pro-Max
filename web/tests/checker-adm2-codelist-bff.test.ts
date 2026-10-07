// AD2-1 to AD2-4, AD2-10 and AD2-11 are not kept: they target qc_fault_type and task_type attrs (now edited on /admin/qc-faults and
// /admin/code-lists by the config lane, with their own validation) and a concurrent-save race the contract cannot close (no ETag on
// PUT /v1/admin/code-lists). This editor serves the classification lists only and refuses attrs it does not know.
// CHECKER ADM-2 (T2): the code-list BFF (F-ADM-011/021/023/060) against CodeItem, the acceptance text and docs/24 s3.4.
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { PUT as codeListPut } from "@/app/api/bff/admin/code-lists/[key]/route";
import { ORIGIN, req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason typed by the administrator";
const put = (key: string, body: unknown, c: Record<string, string>) => codeListPut(req(`/api/bff/admin/code-lists/${key}`, "PUT", body, c), { params: Promise.resolve({ key }) });
const putRaw = (key: string, raw: string, c: Record<string, string>) =>
  codeListPut(
    new NextRequest(`${ORIGIN}/api/bff/admin/code-lists/${key}`, { method: "PUT", body: raw, headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, cookie: Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ") } }),
    { params: Promise.resolve({ key }) },
  );
const stored = (key: string) => (mock.state.codeLists[key] ?? []) as Record<string, unknown>[];



describe("CodeItem members", () => {
  it("AD2-5: valid_to before valid_from is refused (an item that never applies)", async () => {
    const c = await signIn("madmin1");
    const items = stored("channel").map((i) => ({ ...i }));
    items[0] = { ...items[0], valid_from: "2026-06-01", valid_to: "2026-01-01" };
    expect((await put("channel", { items, reason: REASON }, c)).status).toBe(400);
  });
  it("AD2-6: an impossible calendar date (2026-13-45) is refused for valid_to", async () => {
    const c = await signIn("madmin1");
    const items = stored("channel").map((i) => ({ ...i }));
    items[0] = { ...items[0], valid_to: "2026-13-45" };
    expect((await put("channel", { items, reason: REASON }, c)).status).toBe(400);
  });
  it("AD2-7: a label of 61 emoji is 61 code points (120 allowed by the contract) and is accepted", async () => {
    const c = await signIn("madmin1");
    const items = [...stored("channel").map((i) => ({ ...i })), { code: "emoji_one", label_en: "😀".repeat(61), label_bn: null, sort: 9 }];
    expect((await put("channel", { items, reason: REASON }, c)).status).toBe(200);
  });
  it("AD2-8: an attrs object with thousands of members is refused", async () => {
    const c = await signIn("madmin1");
    const attrs = Object.fromEntries(Array.from({ length: 5000 }, (_, i) => [`k${i}`, "v"]));
    const items = stored("channel").map((i) => ({ ...i }));
    items[0] = { ...items[0], attrs };
    expect((await put("channel", { items, reason: REASON }, c)).status).toBe(400);
  });
  it("AD2-9: a __proto__ member of attrs is refused or dropped, never forwarded", async () => {
    const c = await signIn("madmin1");
    const items = JSON.stringify(stored("channel").map((i, n) => (n === 0 ? { ...i, attrs: "@@" } : i))).replace('"@@"', '{"__proto__":"x","ok":"1"}');
    const res = await putRaw("channel", `{"reason":"${REASON}","items":${items}}`, c);
    if (res.status === 200) expect(Object.prototype.hasOwnProperty.call(stored("channel")[0]!.attrs ?? {}, "__proto__")).toBe(false);
    else expect(res.status).toBe(400);
  });
});

