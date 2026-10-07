// Routes (F-ADM-002) and route assignments (F-ADM-003). visit_days_mask: bit0 Sat .. bit6 Fri (contract).
import { businessDate } from "@/lib/i18n";
import { defineAction, defineEntity } from "@/components/admin/crud/meta";
import type { EndAssignmentRequest, Route, RouteAssignment, RouteAssignmentWrite, RoutePatch, RouteWrite } from "@/contract/types";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";
import type { MessageKey } from "@/lib/i18n";

export const WEEK_BITS: readonly MessageKey[] = ["day.sat", "day.sun", "day.mon", "day.tue", "day.wed", "day.thu", "day.fri"];
const ZONE_REF = { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] } as const;
const TERRITORY_REF = { path: "/v1/admin/geo/{level}", params: { level: "territory" }, label: ["code", "name"] } as const;
export const ROUTE_REF = { path: "/v1/admin/routes", label: ["code", "name"] } as const;
export const USER_REF = { path: "/v1/admin/users", label: ["username", "full_name"] } as const;
const WRITE = ["ADMIN", "SUPERADMIN"] as const;

export const routes = defineEntity<Route, RouteWrite, RoutePatch>({
  slug: "routes",
  group: "routes",
  labelKey: "entity.routes",
  singularKey: "entity.routes.singular",
  api: { collection: "/v1/admin/routes", item: "/v1/admin/routes/{id}", get: "/v1/admin/routes/{id}" },
  auditEntity: "route",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "code", labelKey: "entity.field.code", kind: "text", mode: "create-only", required: true, maxLength: 40, pattern: "^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$", column: true },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "zone_id", labelKey: "entity.field.zone_id", kind: "ref", required: true, min: 1, ref: ZONE_REF, column: true },
    {
      name: "kind",
      labelKey: "entity.field.kind",
      kind: "enum",
      mode: "create-only",
      required: true,
      options: ["sr", "amo"],
      optionKeys: { sr: "entity.kind.sr", amo: "entity.kind.amo" },
      column: true,
    },
    {
      name: "visit_kind",
      labelKey: "entity.field.visit_kind",
      kind: "enum",
      nullable: true,
      options: ["daily", "3f", "2f"],
      optionKeys: { daily: "entity.visit.daily", "3f": "entity.visit.3f", "2f": "entity.visit.2f" },
      column: true,
    },
    { name: "visit_days_mask", labelKey: "entity.field.visit_days", kind: "mask", maskBits: WEEK_BITS, required: true, column: true },
    // The printed day text is a label, never a key (contract): kept apart from the route name.
    { name: "display_label", labelKey: "entity.field.display_label", kind: "text", nullable: true, maxLength: 60, column: true },
    { name: "sequence_no", labelKey: "entity.field.sequence_no", kind: "int", nullable: true, min: 1 },
    {
      name: "status",
      labelKey: "entity.field.status",
      kind: "enum",
      mode: "update-only",
      options: ["active", "inactive"],
      optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" },
      column: true,
    },
    // A visit-days change takes effect from this future Dhaka date (contract RoutePatch.effective_from).
    { name: "effective_from", labelKey: "entity.field.effective_from", kind: "date", mode: "update-only", futureOnly: true },
  ],
  filters: [
    { param: "q", kind: "search", labelKey: "common.search" },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: ZONE_REF },
    { param: "territory_id", kind: "ref", labelKey: "scope.territory", ref: TERRITORY_REF },
    { param: "status", kind: "enum", labelKey: "entity.field.status", options: ["active", "inactive"], optionKeys: { active: "entity.status.active", inactive: "entity.status.inactive" } },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: WRITE,
  reasonOnUpdate: "change_reason",
  // REQUEST: docs/requests/web-admin-create-reason.md (RouteWrite has no reason member).
  reasonOnCreate: null,
});

export const routeAssignments = defineEntity<RouteAssignment, RouteAssignmentWrite, Record<never, never>>({
  slug: "route-assignments",
  group: "routes",
  labelKey: "entity.route_assignments",
  singularKey: "entity.route_assignments.singular",
  api: { collection: "/v1/admin/route-assignments" },
  auditEntity: "route_assignment",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "route_id", labelKey: "entity.field.route", kind: "ref", mode: "create-only", required: true, min: 1, ref: ROUTE_REF, column: true },
    { name: "user_id", labelKey: "entity.field.user", kind: "ref", mode: "create-only", required: true, min: 1, ref: USER_REF, column: true },
    {
      name: "kind",
      labelKey: "entity.field.kind",
      kind: "enum",
      mode: "create-only",
      required: true,
      options: ["primary", "cover"],
      optionKeys: { primary: "entity.kind.primary", cover: "entity.kind.cover" },
      column: true,
    },
    { name: "valid_from", labelKey: "entity.field.valid_from", kind: "date", mode: "create-only", required: true, futureOnly: true, column: true },
    { name: "valid_to", labelKey: "entity.field.valid_to", kind: "date", mode: "create-only", nullable: true, futureOnly: true, column: true },
  ],
  filters: [
    { param: "route_id", kind: "ref", labelKey: "entity.field.route", ref: ROUTE_REF },
    { param: "user_id", kind: "ref", labelKey: "entity.field.user", ref: USER_REF },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: ZONE_REF },
    { param: "valid_on", kind: "date", labelKey: "entity.field.valid_on" },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: WRITE,
  // Assignments are never edited or deleted: a wrong one is ended (history stays, nothing historic is re-attributed).
  reasonOnUpdate: null,
  reasonOnCreate: "reason",
  reasonMax: 300, // RouteAssignmentWrite.reason maxLength
  actions: [
    defineAction<EndAssignmentRequest>({
      key: "end",
      labelKey: "action.end_assignment",
      path: "/v1/admin/route-assignments/{id}/end",
      fields: [{ name: "valid_to", labelKey: "entity.field.valid_to", kind: "date", required: true, futureOnly: true }],
      reasonMember: "reason",
      when: (row) => row.valid_to === null || row.valid_to === undefined || String(row.valid_to) > businessDate(),
    }),
  ],
});
