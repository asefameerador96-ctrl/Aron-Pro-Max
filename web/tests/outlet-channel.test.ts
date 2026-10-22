// Outlet channel is mandatory (docs/34 s2: outlet.channel NOT NULL): GT, DCC, Astha, RCC, MT, HoReCa. Astha here is a channel, not the loyalty programme.
import { describe, expect, it } from "vitest";
import { outlets } from "@/app/admin/_entities/outlets";
import { valuesSchema } from "@/components/admin/crud/validation";
import { setupMock } from "./helpers/bff";

const { signIn, create, update } = setupMock();
const REASON = "Reason that is long enough";
const base = { name: "Fresh Store", owner_name: "Owner One", zone_id: "14", cluster_id: "1", outlet_kind: "retail" };

describe("outlet channel", () => {
  it("is a required, non-nullable select with exactly the six channels", () => {
    const f = outlets.fields.find((x) => x.name === "channel")!;
    expect(f).toMatchObject({ kind: "enum", required: true });
    expect(f.nullable).toBeFalsy();
    expect(f.options).toEqual(["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"]);
  });
  it("create without a channel, or with an empty or unknown one, is refused; each channel is accepted", () => {
    const c = valuesSchema(outlets, "create");
    expect(c.safeParse(base).success).toBe(false);
    expect(c.safeParse({ ...base, channel: "" }).success).toBe(false);
    expect(c.safeParse({ ...base, channel: "XX" }).success).toBe(false);
    for (const ch of ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"]) expect(c.safeParse({ ...base, channel: ch }).success).toBe(true);
  });
  it("an edit cannot clear the channel", () => {
    const u = valuesSchema(outlets, "update");
    expect(u.safeParse({ channel: "" }).success).toBe(false);
    expect(u.safeParse({ channel: null }).success).toBe(false);
  });
  it("through the proxy: create without channel is 400 and nothing reaches the API; an edit may change the channel", async () => {
    const c = await signIn("madmin1");
    expect((await create("outlets", { values: base, reason: REASON }, c)).status).toBe(400);
    expect((await create("outlets", { values: { ...base, channel: "Astha" }, reason: REASON }, c)).status).toBe(201);
    expect((await update("outlets", "1", { values: { channel: "MT" }, reason: REASON, version: 1 }, c)).status).toBe(200);
    expect((await update("outlets", "1", { values: { channel: "" }, reason: REASON, version: 2 }, c)).status).toBe(400);
  });
});
