import { describe, expect, it } from "vitest";
import { clusters } from "@/app/admin/_entities/clusters";
import { ENTITIES, entityBySlug } from "@/app/admin/_entities/registry";
import { isWritable } from "@/components/admin/crud/meta";
import { reasonSchema, valuesSchema } from "@/components/admin/crud/validation";
import { en } from "@/lib/i18n/messages-en";

describe("entity metadata → validation", () => {
  it("create: required fields, trimming, nullable empty → null, strict members", () => {
    const s = valuesSchema(clusters, "create");
    expect(s.safeParse({ name: "  Haat  ", zone_id: "4", cluster_type: "" })).toMatchObject({ success: true, data: { name: "Haat", zone_id: 4, cluster_type: null } });
    expect(s.safeParse({ zone_id: 1 }).success).toBe(false);
    expect(s.safeParse({ name: "A" }).success).toBe(false);
    expect(s.safeParse({ name: "A", zone_id: 1, status: "active" }).success).toBe(false); // update-only
    expect(s.safeParse({ name: "A", zone_id: 1, id: 5 }).success).toBe(false); // readonly
    expect(s.safeParse({ name: "x".repeat(121), zone_id: 1 }).success).toBe(false);
    expect(s.safeParse({ name: "A", zone_id: 0 }).success).toBe(false);
    expect(s.safeParse({ name: "A", zone_id: 1.5 }).success).toBe(false);
  });
  it("update: every field optional, status enum, create-only/readonly refused", () => {
    const s = valuesSchema(clusters, "update");
    expect(s.safeParse({ status: "inactive" }).success).toBe(true);
    expect(s.safeParse({ status: "closed" }).success).toBe(false);
    expect(s.safeParse({ name: "" }).success).toBe(false);
    expect(s.safeParse({ updated_at: "x" }).success).toBe(false);
  });
  it("reason: 10 to 500 characters after trimming (ChangeReason)", () => {
    expect(reasonSchema.safeParse("123456789").success).toBe(false);
    expect(reasonSchema.safeParse("1234567890").success).toBe(true);
    expect(reasonSchema.safeParse("   123456789   ").success).toBe(false);
    expect(reasonSchema.safeParse("x".repeat(501)).success).toBe(false);
  });
  it("every entity label and field label exists in the catalogue; slugs are unique; reasons are wired", () => {
    expect(new Set(ENTITIES.map((e) => e.slug)).size).toBe(ENTITIES.length);
    for (const e of ENTITIES) {
      expect(en[e.labelKey], e.slug).toBeTruthy();
      expect(en[e.singularKey], e.slug).toBeTruthy();
      expect(e.reasonOnUpdate).toBeTruthy();
      for (const f of e.fields) {
        expect(en[f.labelKey], `${e.slug}.${f.name}`).toBeTruthy();
        for (const k of Object.values(f.optionKeys ?? {})) expect(en[k]).toBeTruthy();
      }
      expect(e.fields.some((f) => isWritable(f, "update"))).toBe(true);
      expect(e.writeRoles.every((r) => e.readRoles.includes(r))).toBe(true);
    }
    expect(entityBySlug("clusters")).toBe(clusters);
    expect(entityBySlug("nope")).toBeUndefined();
  });
});
