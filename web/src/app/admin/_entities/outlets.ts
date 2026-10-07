// Outlets (F-ADM-010 CRUD, F-ADM-070 reactivation, F-WEB-003 retailer detail) and the Outlet Approval Panel (F-WEB-032).
// The edit page IS the retailer detail: four sections (basic info, address, business, additional detail).
import { defineAction, defineEntity } from "@/components/admin/crud/meta";
import type { Outlet, OutletPatch, OutletRequestApproveRequest, OutletRequestSummary, OutletRequestVerifyRequest, OutletWrite, ReasonRequest } from "@/contract/types";
import { ADMIN_PORTAL_ROLES, OUTLET_REQUEST_ACT_ROLES, OUTLET_REQUEST_READ_ROLES } from "@/lib/auth/roles";

const CHANNELS = { options: ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"], optionKeys: { GT: "channel.GT", DCC: "channel.DCC", Astha: "channel.Astha", RCC: "channel.RCC", MT: "channel.MT", HoReCa: "channel.HoReCa" } } as const;
const GEO = { options: ["Hill", "Urban", "SemiUrban", "Rural"], optionKeys: { Hill: "geoclass.Hill", Urban: "geoclass.Urban", SemiUrban: "geoclass.SemiUrban", Rural: "geoclass.Rural" } } as const;
const KINDS = { options: ["retail", "wholesale"], optionKeys: { retail: "outlet.kind.retail", wholesale: "outlet.kind.wholesale" } } as const;
const STATUS = { options: ["active", "closed", "merged", "archived"], optionKeys: { active: "outlet.status.active", closed: "outlet.status.closed", merged: "outlet.status.merged", archived: "outlet.status.archived" } } as const;
const ZONE_REF = { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] } as const;
const CLUSTER_REF = { path: "/v1/admin/clusters", label: ["name"] } as const;
const ROUTE_REF = { path: "/v1/admin/routes", label: ["code", "name"] } as const;
const WRITE = ["ADMIN", "SUPERADMIN"] as const;

export const outlets = defineEntity<Outlet, OutletWrite, OutletPatch>({
  slug: "outlets",
  group: "outlets",
  labelKey: "entity.outlets",
  singularKey: "entity.outlets.singular",
  api: { collection: "/v1/admin/outlets", item: "/v1/admin/outlets/{id}", get: "/v1/admin/outlets/{id}" },
  auditEntity: "outlet",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "code", labelKey: "entity.field.code", kind: "text", mode: "create-only", nullable: true, maxLength: 32, pattern: "^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$", column: true, section: "outlet.section.basic" },
    { name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true, section: "outlet.section.basic" },
    { name: "name_bn", labelKey: "entity.field.name_bn", kind: "text", nullable: true, maxLength: 120, section: "outlet.section.basic" },
    { name: "owner_name", labelKey: "entity.field.owner_name", kind: "text", required: true, maxLength: 120, column: true, section: "outlet.section.basic" },
    { name: "contact_number", labelKey: "entity.field.contact_number", kind: "text", nullable: true, maxLength: 11, pattern: "^01[3-9]\\d{8}$", normalizeDigits: true, section: "outlet.section.basic" },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "update-only", ...STATUS, column: true, section: "outlet.section.basic" },
    { name: "address", labelKey: "entity.field.address", kind: "text", nullable: true, maxLength: 300, section: "outlet.section.address" },
    { name: "zone_id", labelKey: "entity.field.zone_id", kind: "ref", mode: "create-only", required: true, min: 1, ref: ZONE_REF, column: true, section: "outlet.section.address" },
    { name: "cluster_id", labelKey: "entity.field.cluster", kind: "ref", required: true, min: 1, ref: CLUSTER_REF, section: "outlet.section.address" },
    { name: "route_id", labelKey: "entity.field.route", kind: "ref", nullable: true, min: 1, ref: ROUTE_REF, column: true, section: "outlet.section.address" },
    { name: "lat", labelKey: "entity.field.lat", kind: "number", min: -90, max: 90, section: "outlet.section.address" },
    { name: "lng", labelKey: "entity.field.lng", kind: "number", min: -180, max: 180, section: "outlet.section.address" },
    { name: "geo_class", labelKey: "entity.field.geo_class", kind: "enum", nullable: true, ...GEO, section: "outlet.section.address" },
    { name: "channel", labelKey: "entity.field.channel", kind: "enum", required: true, ...CHANNELS, column: true, section: "outlet.section.business" },
    // sub_channel_id is an id, but the contract lists sub-channels only as code-list CODES: a plain number until docs/requests/web-admin-sub-channel-ids.md is answered.
    { name: "sub_channel_id", labelKey: "entity.field.sub_channel_id", kind: "int", nullable: true, min: 1, section: "outlet.section.business" },
    { name: "outlet_kind", labelKey: "entity.field.outlet_kind", kind: "enum", required: true, ...KINDS, column: true, section: "outlet.section.business" },
    { name: "price_type", labelKey: "entity.field.price_type", kind: "text", mode: "readonly", section: "outlet.section.business" },
    { name: "visit_sequence", labelKey: "entity.field.visit_sequence", kind: "int", mode: "update-only", nullable: true, min: 1, section: "outlet.section.business" },
    { name: "external_ref", labelKey: "entity.field.external_ref", kind: "text", nullable: true, maxLength: 64, section: "outlet.section.additional" },
    { name: "location_confirmed", labelKey: "entity.field.location_confirmed", kind: "bool", mode: "readonly", section: "outlet.section.additional" },
  ],
  filters: [
    { param: "q", kind: "search", labelKey: "common.search" },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: ZONE_REF },
    { param: "route_id", kind: "ref", labelKey: "entity.field.route", ref: ROUTE_REF },
    { param: "cluster_id", kind: "ref", labelKey: "entity.field.cluster", ref: CLUSTER_REF },
    { param: "status", kind: "enum", labelKey: "entity.field.status", ...STATUS },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: WRITE,
  reasonOnUpdate: "change_reason",
  reasonOnCreate: "change_reason",
  actions: [
    // F-ADM-070: a wrongly closed outlet goes back to active with a reason (PATCH status, If-Match).
    defineAction<OutletPatch>({
      key: "reopen",
      labelKey: "action.reopen_outlet",
      path: "/v1/admin/outlets/{id}",
      method: "PATCH",
      ifMatch: true,
      fields: [],
      fixed: { status: "active" },
      reasonMember: "change_reason",
      when: (row) => row.status === "closed",
    }),
  ],
});

const OUTLET_REQ_STATUS = { options: ["pending", "verified", "approved", "rejected", "lapsed", "discarded"], optionKeys: { pending: "req.status.pending", verified: "req.status.verified", approved: "req.status.approved", rejected: "req.status.rejected", lapsed: "req.status.lapsed", discarded: "req.status.discarded" } } as const;
const OUTLET_REQ_TYPE = { options: ["new", "close", "info", "cluster", "location", "route_add"], optionKeys: { new: "req.type.new", close: "req.type.close", info: "req.type.info", cluster: "req.type.cluster", location: "req.type.location", route_add: "req.type.route_add" } } as const;

export const outletRequests = defineEntity<OutletRequestSummary, Record<never, never>, Record<never, never>>({
  slug: "outlet-requests",
  group: "outlets",
  labelKey: "entity.outlet_requests",
  singularKey: "entity.outlet_requests.singular",
  api: { collection: "/v1/outlet-requests", get: "/v1/outlet-requests/{request_uuid}" },
  auditEntity: "outlet_request",
  idField: "request_uuid",
  idKind: "uuid",
  canCreate: false,
  fields: [
    { name: "request_type", labelKey: "entity.field.request_type", kind: "enum", mode: "readonly", ...OUTLET_REQ_TYPE, column: true },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "readonly", ...OUTLET_REQ_STATUS, column: true },
    { name: "outlet_name", labelKey: "entity.field.name", kind: "text", mode: "readonly", column: true },
    { name: "requested_by_user_id", labelKey: "entity.field.user", kind: "ref", mode: "readonly", ref: { path: "/v1/admin/users", label: ["username", "full_name"] }, column: true },
    { name: "requested_at", labelKey: "entity.field.requested_at", kind: "timestamp", mode: "readonly", column: true },
    { name: "rejection_reason", labelKey: "entity.field.rejection_reason", kind: "text", mode: "readonly", column: true },
  ],
  filters: [
    { param: "request_type", kind: "enum", labelKey: "entity.field.request_type", ...OUTLET_REQ_TYPE },
    { param: "status", kind: "enum", labelKey: "entity.field.status", ...OUTLET_REQ_STATUS },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: ZONE_REF },
  ],
  readRoles: OUTLET_REQUEST_READ_ROLES,
  writeRoles: OUTLET_REQUEST_ACT_ROLES,
  reasonOnUpdate: null,
  reasonOnCreate: null,
  actions: [
    defineAction<OutletRequestVerifyRequest>({
      key: "verify",
      labelKey: "action.verify_request",
      path: "/v1/outlet-requests/{request_uuid}/verify",
      fields: [
        { name: "sub_channel_id", labelKey: "entity.field.sub_channel_id", kind: "int", required: true, min: 1 },
        { name: "geo_class", labelKey: "entity.field.geo_class", kind: "enum", nullable: true, required: false, ...GEO },
      ],
      reasonMember: "note", // note (<= 500) doubles as the mandatory reason
      when: (row) => row.status === "pending",
    }),
    defineAction<OutletRequestApproveRequest>({
      key: "approve",
      labelKey: "action.approve_request",
      path: "/v1/outlet-requests/{request_uuid}/approve",
      fields: [
        { name: "outlet_code", labelKey: "entity.field.code", kind: "text", nullable: true, required: false, maxLength: 32, pattern: "^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$" },
        { name: "route_id", labelKey: "entity.field.route", kind: "ref", nullable: true, required: false, min: 1, ref: ROUTE_REF },
      ],
      reasonMember: "change_reason",
      when: (row) => row.status === "verified",
    }),
    defineAction<ReasonRequest>({
      key: "reject",
      labelKey: "action.reject_request",
      path: "/v1/outlet-requests/{request_uuid}/reject",
      fields: [],
      reasonMember: "reason",
      when: (row) => row.status === "pending" || row.status === "verified",
    }),
  ],
});
