// CHECKER OCH (T1) for F-WEB-003: drives components/admin/outlet-change-form.tsx without a DOM (state cells mocked, as in checker-adm8-radius-ui).
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { OutletChangeForm, type OutletFacts } from "@/components/admin/outlet-change-form";
import { t as tr, type Locale, type MessageKey } from "@/lib/i18n";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, calls: [] as { op: string; args: { body: Record<string, unknown>; reason: string } }[], replies: [] as unknown[] }));
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
vi.mock("@/components/i18n-provider", () => ({
  useI18n: () => ({ locale: "en", t: (k: MessageKey, v?: Record<string, string | number>) => tr("en" as Locale, k, v), number: (n: number) => String(n), problem: (c: string) => String(c) }),
}));
vi.mock("@/components/admin/master-op-client", () => ({
  callMasterOp: async (op: string, args: { body: Record<string, unknown>; reason: string }) => {
    hooks.calls.push({ op, args });
    return hooks.replies.shift() ?? { ok: true, data: {} };
  },
}));

type El = { type?: unknown; props: Record<string, unknown> };
function walk(n: unknown, f: (e: El) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (!n || typeof n !== "object") return;
  const e = n as { props?: Record<string, unknown>; type?: unknown };
  if (e.props) {
    f(e as El);
    walk(e.props.children, f);
    if (typeof e.type === "function") {
      try {
        walk((e.type as (p: unknown) => unknown)(e.props), f);
      } catch {
        /* hooks outside render */
      }
    }
  }
}
const OUTLET: OutletFacts = { id: 1, code: "DHK-334-001", name: "Banani Store", name_bn: "", owner_name: "Md. Rahim", contact_number: "", address: "Road 11, Banani", cluster_id: "1", sub_channel_id: "11", lat: "23.7937", lng: "90.4066" };
let props = { outlet: OUTLET, clusters: [{ value: "1", label: "C1" }, { value: "2", label: "C2" }], subChannels: [{ value: "11", label: "Grocery" }], closeReasons: [{ value: "shop_shut", label: "Shut" }] };
function render() {
  hooks.i = 0;
  const tree = (OutletChangeForm as unknown as (p: unknown) => ReactElement)(props);
  const found: El[] = [];
  walk(tree, (e) => found.push(e));
  return { find: (p: (e: El) => boolean) => found.find(p), all: found };
}
const fill = (id: string, v: string) => (render().find((e) => e.props.id === id)!.props.onChange as (ev: unknown) => void)({ target: { value: v } });
const submit = async () => {
  const f = render().find((e) => e.props["data-testid"] === "outlet-change-form")!;
  (f.props.onSubmit as (ev: unknown) => void)({ preventDefault() {} });
  await new Promise((r) => setTimeout(r, 0));
};
const REASON = "Owner name is spelt wrong on the memo";

beforeEach(() => {
  hooks.cells = [];
  hooks.calls = [];
  hooks.replies = [];
  props = { outlet: OUTLET, clusters: [{ value: "1", label: "C1" }, { value: "2", label: "C2" }], subChannels: [{ value: "11", label: "Grocery" }], closeReasons: [{ value: "shop_shut", label: "Shut" }] };
});

describe("info diffing", () => {
  it("sends only changed members; Bengali-digit phone normalised; the reason goes as reason (BFF maps to note)", async () => {
    fill("f-owner_name", "Md. Rahim Uddin");
    fill("f-contact_number", "০১৭১২৩৪৫৬৭৮");
    fill("reason", REASON);
    await submit();
    expect(hooks.calls).toHaveLength(1);
    expect(hooks.calls[0]!.op).toBe("outlet-request.create");
    expect(hooks.calls[0]!.args.body.proposed).toEqual({ owner_name: "Md. Rahim Uddin", contact_number: "01712345678" });
    expect(Object.keys(hooks.calls[0]!.args.body).sort()).toEqual(["outlet_id", "proposed", "request_type", "request_uuid"]);
  });
  it("no change is refused locally; clearing name or address is refused locally", async () => {
    fill("reason", REASON);
    await submit();
    fill("f-name", "  ");
    await submit();
    fill("f-name", "Banani Store");
    fill("f-address", "");
    await submit();
    expect(hooks.calls).toHaveLength(0);
  });
  it("clearing the sub-channel is not silently reported as 'no change'", async () => {
    fill("f-sub_channel_id", "");
    fill("reason", REASON);
    await submit();
    // Either the clear is sent (sub_channel_id null, contract allows it) or the user is told it cannot be cleared; "nothing is different" is wrong.
    const errs = hooks.cells[4] as Record<string, string>;
    expect(hooks.calls.length === 1 || errs.sub_channel_id !== undefined).toBe(true);
  });
});

describe("reason edge cases", () => {
  it("9 code points and zero-width padding are refused, 10 emoji code points pass, 501 refused", async () => {
    for (const r of ["a".repeat(9), `​${"a".repeat(5)}‍${"b".repeat(4)}`, "x".repeat(501)]) {
      fill("f-name", "Banani Store Ltd");
      fill("reason", r);
      await submit();
    }
    expect(hooks.calls).toHaveLength(0);
    fill("reason", "😀".repeat(10));
    await submit();
    expect(hooks.calls).toHaveLength(1);
  });
});

describe("idempotency key", () => {
  it("a retry after a failure reuses the uuid; after a success the next submit gets a new one", async () => {
    hooks.replies.push({ ok: false, status: 0, problem: { code: "ERR_SERVICE_UNAVAILABLE" } });
    fill("f-name", "Banani Store Ltd");
    fill("reason", REASON);
    await submit();
    await submit();
    expect(hooks.calls[0]!.args.body.request_uuid).toBe(hooks.calls[1]!.args.body.request_uuid);
    fill("reason", REASON);
    await submit();
    expect(hooks.calls[2]!.args.body.request_uuid).not.toBe(hooks.calls[1]!.args.body.request_uuid);
  });
  it("double submit while busy: at most one call, or the same uuid", async () => {
    fill("f-name", "Banani Store Ltd");
    fill("reason", REASON);
    const f = render().find((e) => e.props["data-testid"] === "outlet-change-form")!;
    (f.props.onSubmit as (ev: unknown) => void)({ preventDefault() {} });
    (f.props.onSubmit as (ev: unknown) => void)({ preventDefault() {} });
    await new Promise((r) => setTimeout(r, 0));
    expect(new Set(hooks.calls.map((c) => c.args.body.request_uuid)).size).toBe(1);
  });
  it("after an ambiguous failure, changing WHAT is proposed must not reuse the uuid (replay returns the stale request, or 409 for a new type)", async () => {
    hooks.replies.push({ ok: false, status: 0, problem: { code: "ERR_SERVICE_UNAVAILABLE" } });
    fill("f-name", "Banani Store Ltd");
    fill("reason", REASON);
    await submit();
    fill("f-request_type", "close");
    fill("f-close_reason_code", "shop_shut");
    await submit();
    expect(hooks.calls).toHaveLength(2);
    expect(hooks.calls[1]!.args.body.request_type).toBe("close");
    expect(hooks.calls[1]!.args.body.request_uuid).not.toBe(hooks.calls[0]!.args.body.request_uuid);
  });
});

describe("cluster select", () => {
  it("an outlet whose cluster is not among the options: the shown option is what gets sent", async () => {
    props = { ...props, outlet: { ...OUTLET, cluster_id: "" } };
    fill("f-request_type", "cluster");
    fill("reason", REASON);
    await submit(); // a placeholder option is shown (nothing is picked), so the send is refused instead of sending a hidden value
    expect(hooks.calls.length).toBe(0);
    expect(render().find((e) => e.props.id === "f-cluster_id")!.props.value).toBe("");
  });
});

describe("a11y", () => {
  it("every control has a label (htmlFor) and an invalid field is marked aria-invalid / described by its error", async () => {
    fill("f-name", "x");
    fill("reason", REASON);
    await submit();
    const r = render();
    for (const id of ["f-request_type", "f-name", "f-name_bn", "f-owner_name", "f-contact_number", "f-address", "f-sub_channel_id", "reason"]) expect(r.find((e) => e.type === "label" && e.props.htmlFor === id), id).toBeTruthy();
    const name = r.find((e) => e.props.id === "f-name")!;
    expect(name.props["aria-invalid"] === true || name.props["aria-invalid"] === "true" || typeof name.props["aria-describedby"] === "string").toBe(true);
  });
});
