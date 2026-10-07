// Geography (F-ADM-001): wing, division, territory, house and zone share one contract path /v1/admin/geo/{level}, so one
// factory makes the five entities. A node's parent is the level above it (assumption, DECISIONS in docs/status/web-admin.md).
import { defineEntity, type AnyEntity, type FieldMeta } from "@/components/admin/crud/meta";
import type { GeoLevel, GeoNode, GeoNodePatch, GeoNodeWrite } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

const ORDER: readonly GeoLevel[] = ["wing", "division", "territory", "house", "zone"];
type F = FieldMeta<GeoNode, GeoNodeWrite, GeoNodePatch>;

const KEYS = {
  wing: ["entity.wings", "entity.wings.singular"],
  division: ["entity.divisions", "entity.divisions.singular"],
  territory: ["entity.territories", "entity.territories.singular"],
  house: ["entity.houses", "entity.houses.singular"],
  zone: ["entity.zones", "entity.zones.singular"],
} as const satisfies Record<GeoLevel, readonly [MessageKey, MessageKey]>;

const SLUG: Record<GeoLevel, string> = { wing: "wings", division: "divisions", territory: "territories", house: "houses", zone: "zones" };

function geoEntity(level: GeoLevel): AnyEntity {
  const parent = ORDER[ORDER.indexOf(level) - 1];
  const fields: F[] = [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "code", labelKey: "entity.field.code", kind: "text", mode: "create-only", required: true, maxLength: 40, pattern: "^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$", column: true },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "name_bn", labelKey: "entity.field.name_bn", kind: "text", nullable: true, maxLength: 120, column: true },
  ];
  if (parent) {
    fields.push({
      name: "parent_id",
      labelKey: "entity.field.parent",
      kind: "ref",
      required: true,
      min: 1,
      ref: { path: "/v1/admin/geo/{level}", params: { level: parent }, label: ["code", "name"] },
      column: true,
    });
  }
  if (level === "zone") fields.push({ name: "dep_name", labelKey: "entity.field.dep_name", kind: "text", nullable: true, maxLength: 120 });
  fields.push(
    { name: "email", labelKey: "entity.field.email", kind: "text", nullable: true, maxLength: 120, pattern: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$" },
    { name: "address", labelKey: "entity.field.address", kind: "text", nullable: true, maxLength: 300 },
    { name: "pda_contact_no", labelKey: "entity.field.pda_contact_no", kind: "text", nullable: true, maxLength: 16, pattern: "^\\+?[0-9]{5,15}$" },
    {
      name: "status",
      labelKey: "entity.field.status",
      kind: "enum",
      mode: "update-only",
      options: ["active", "inactive"],
      optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" },
      column: true,
    },
  );
  return defineEntity<GeoNode, GeoNodeWrite, GeoNodePatch>({
    slug: SLUG[level],
    group: "geography",
    labelKey: KEYS[level][0],
    singularKey: KEYS[level][1],
    params: { level },
    api: { collection: "/v1/admin/geo/{level}", item: "/v1/admin/geo/{level}/{id}", get: "/v1/admin/geo/{level}/{id}" },
    auditEntity: "geo_node",
    idField: "id",
    fields,
    filters: [
      { param: "q", kind: "search", labelKey: "common.search" },
      ...(parent ? [{ param: "parent_id", kind: "ref" as const, labelKey: "entity.field.parent" as const, ref: { path: "/v1/admin/geo/{level}" as const, params: { level: parent }, label: ["code", "name"] } }] : []),
      { param: "status", kind: "enum", labelKey: "entity.field.status", options: ["active", "inactive"], optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" } },
    ],
    readRoles: ADMIN_PORTAL_ROLES,
    writeRoles: ["ADMIN", "SUPERADMIN"],
    reasonOnUpdate: "change_reason",
    reasonOnCreate: "change_reason",
  });
}

export const geoEntities: readonly AnyEntity[] = ORDER.map(geoEntity);
