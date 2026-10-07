// Proof entity of the CRUD generator (N-011): clusters, an outlet group inside a zone. This one object produces the list,
// filter, create and edit pages and the audited BFF writes. See web/README.md "Add an entity in one short file".
import { defineEntity } from "@/components/admin/crud/meta";
import type { Cluster, ClusterPatch, ClusterWrite } from "@/contract/types";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

export const clusters = defineEntity<Cluster, ClusterWrite, ClusterPatch>({
  slug: "clusters",
  group: "geography",
  labelKey: "entity.clusters",
  singularKey: "entity.clusters.singular",
  api: { collection: "/v1/admin/clusters", item: "/v1/admin/clusters/{id}" },
  auditEntity: "cluster",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "zone_id", labelKey: "entity.field.zone_id", kind: "ref", required: true, min: 1, ref: { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] }, column: true },
    { name: "cluster_type", labelKey: "entity.field.cluster_type", kind: "text", nullable: true, maxLength: 60, column: true },
    {
      name: "status",
      labelKey: "entity.field.status",
      kind: "enum",
      mode: "update-only",
      options: ["active", "inactive"],
      optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" },
      column: true,
    },
    { name: "updated_at", labelKey: "entity.field.updated_at", kind: "timestamp", mode: "readonly", column: true },
  ],
  filters: [
    { param: "q", kind: "search", labelKey: "common.search" },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] } },
    {
      param: "status",
      kind: "enum",
      labelKey: "entity.field.status",
      options: ["active", "inactive"],
      optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" },
    },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: ["ADMIN", "SUPERADMIN"],
  reasonOnUpdate: "change_reason",
  // REQUEST: docs/requests/web-admin-create-reason.md (ClusterWrite has no reason member yet).
  reasonOnCreate: null,
});
