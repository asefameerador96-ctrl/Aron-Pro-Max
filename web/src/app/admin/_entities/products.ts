// Product hierarchy (F-ADM-004): category > segment > brand > variant share /v1/admin/product-nodes/{level}; SKUs hang
// off a variant. SKU code, variant, category, base unit, pack size and entry unit are create-only: the contract allows
// no PATCH of them (a unit or pack change is a future-dated product change, not an edit).
import { defineEntity, type AnyEntity, type FieldMeta } from "@/components/admin/crud/meta";
import type { ProductNode, ProductNodePatch, ProductNodeWrite, ProductTreeLevel, Sku, SkuPatch, SkuWrite } from "@/contract/types";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";
import type { MessageKey } from "@/lib/i18n";

const ORDER: readonly ProductTreeLevel[] = ["category", "segment", "brand", "variant"];
const ACTIVE = { options: ["active", "inactive"], optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" } } as const;
const WRITE = ["ADMIN", "SUPERADMIN"] as const;
type F = FieldMeta<ProductNode, ProductNodeWrite, ProductNodePatch>;

const KEYS = {
  category: ["entity.categories", "entity.categories.singular"],
  segment: ["entity.segments", "entity.segments.singular"],
  brand: ["entity.brands", "entity.brands.singular"],
  variant: ["entity.variants", "entity.variants.singular"],
} as const satisfies Record<ProductTreeLevel, readonly [MessageKey, MessageKey]>;
const SLUG: Record<ProductTreeLevel, string> = { category: "categories", segment: "segments", brand: "brands", variant: "variants" };

function nodeEntity(level: ProductTreeLevel): AnyEntity {
  const parent = ORDER[ORDER.indexOf(level) - 1];
  const fields: F[] = [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "code", labelKey: "entity.field.code", kind: "text", mode: "create-only", nullable: true, maxLength: 40, column: true },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "name_bn", labelKey: "entity.field.name_bn", kind: "text", nullable: true, maxLength: 120 },
  ];
  if (parent) fields.push({ name: "parent_id", labelKey: "entity.field.parent", kind: "ref", required: true, min: 1, ref: { path: "/v1/admin/product-nodes/{level}", params: { level: parent }, label: ["name"] }, column: true });
  fields.push(
    { name: "sort", labelKey: "entity.field.sort", kind: "int", required: true, min: 0, column: true },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "update-only", ...ACTIVE, column: true },
  );
  return defineEntity<ProductNode, ProductNodeWrite, ProductNodePatch>({
    slug: SLUG[level],
    group: "products",
    labelKey: KEYS[level][0],
    singularKey: KEYS[level][1],
    params: { level },
    api: { collection: "/v1/admin/product-nodes/{level}", item: "/v1/admin/product-nodes/{level}/{id}" },
    auditEntity: "product_node",
    idField: "id",
    fields,
    filters: [
      ...(parent ? [{ param: "parent_id", kind: "ref" as const, labelKey: "entity.field.parent" as const, ref: { path: "/v1/admin/product-nodes/{level}" as const, params: { level: parent }, label: ["name"] } }] : []),
      { param: "status", kind: "enum", labelKey: "entity.field.status", ...ACTIVE },
    ],
    readRoles: ADMIN_PORTAL_ROLES,
    writeRoles: WRITE,
    // REQUEST: docs/requests/web-admin-create-reason.md (product-node writes carry no reason member at all).
    reasonOnUpdate: null,
    reasonOnCreate: null,
  });
}

export const productNodeEntities: readonly AnyEntity[] = ORDER.map(nodeEntity);

const UNITS = { options: ["stick", "piece", "dozen"], optionKeys: { stick: "unit.stick", piece: "unit.piece", dozen: "unit.dozen" } } as const;
const ENTRY_UNITS = { options: ["stick", "piece", "dozen", "pack"], optionKeys: { stick: "unit.stick", piece: "unit.piece", dozen: "unit.dozen", pack: "unit.pack" } } as const;
const REPORT_UNITS = { options: ["stick", "piece", "dozen", "box", "pack"], optionKeys: { stick: "unit.stick", piece: "unit.piece", dozen: "unit.dozen", box: "unit.box", pack: "unit.pack" } } as const;
const VARIANT_REF = { path: "/v1/admin/product-nodes/{level}", params: { level: "variant" }, label: ["name"] } as const;

export const skus = defineEntity<Sku, SkuWrite, SkuPatch>({
  slug: "skus",
  group: "products",
  labelKey: "entity.skus",
  singularKey: "entity.skus.singular",
  api: { collection: "/v1/admin/skus", item: "/v1/admin/skus/{id}" },
  auditEntity: "sku",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "code", labelKey: "entity.field.code", kind: "text", mode: "create-only", required: true, maxLength: 40, pattern: "^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$", column: true },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "short_name", labelKey: "entity.field.short_name", kind: "text", required: true, maxLength: 20, column: true },
    { name: "name_bn", labelKey: "entity.field.name_bn", kind: "text", nullable: true, maxLength: 120 },
    { name: "variant_id", labelKey: "entity.field.variant", kind: "ref", mode: "create-only", required: true, min: 1, ref: VARIANT_REF, column: true },
    {
      name: "category_code",
      labelKey: "entity.field.category",
      kind: "enum",
      mode: "create-only",
      required: true,
      options: ["cigarette", "bidi", "lighter", "match"],
      optionKeys: { cigarette: "sku.cat.cigarette", bidi: "sku.cat.bidi", lighter: "sku.cat.lighter", match: "sku.cat.match" },
      column: true,
    },
    { name: "base_unit", labelKey: "entity.field.base_unit", kind: "enum", mode: "create-only", required: true, ...UNITS, column: true },
    { name: "base_per_pack", labelKey: "entity.field.base_per_pack", kind: "int", mode: "create-only", required: true, min: 1, max: 1000, column: true },
    { name: "entry_unit_default", labelKey: "entity.field.entry_unit", kind: "enum", mode: "create-only", required: true, ...ENTRY_UNITS },
    { name: "report_unit", labelKey: "entity.field.report_unit", kind: "enum", nullable: true, ...REPORT_UNITS },
    { name: "report_factor", labelKey: "entity.field.report_factor", kind: "text", required: true, maxLength: 18, pattern: "^-?\\d{1,13}(\\.\\d{1,3})?$", normalizeDigits: true },
    { name: "sort", labelKey: "entity.field.sort", kind: "int", required: true, min: 0, column: true },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "update-only", ...ACTIVE, column: true },
  ],
  filters: [
    { param: "q", kind: "search", labelKey: "common.search" },
    { param: "status", kind: "enum", labelKey: "entity.field.status", ...ACTIVE },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: WRITE,
  reasonOnUpdate: "change_reason",
  // REQUEST: docs/requests/web-admin-create-reason.md (SkuWrite has no reason member).
  reasonOnCreate: null,
});
