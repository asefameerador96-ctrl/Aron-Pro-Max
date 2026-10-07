// Working-day calendar (F-ADM-033): holidays, make-up days and emergency off-days. Declarations are never edited or
// deleted (contract: POST only); the list is a date window, not a cursor page.
import { defineEntity } from "@/components/admin/crud/meta";
import type { Holiday, HolidayWrite } from "@/contract/types";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

const KINDS = { options: ["holiday", "makeup_day", "emergency_off"], optionKeys: { holiday: "holiday.holiday", makeup_day: "holiday.makeup_day", emergency_off: "holiday.emergency_off" } } as const;
const SCOPES = { options: ["global", "wing", "division", "territory", "zone"], optionKeys: { global: "scope.global", wing: "scope.wing", division: "scope.division", territory: "scope.territory", zone: "scope.zone" } } as const;

export const holidays = defineEntity<Holiday, HolidayWrite, Record<never, never>>({
  slug: "holidays",
  group: "lists",
  labelKey: "entity.holidays",
  singularKey: "entity.holidays.singular",
  api: { collection: "/v1/admin/calendar/holidays" },
  auditEntity: "calendar_holiday",
  idField: "id",
  fields: [
    { name: "date", labelKey: "entity.field.date", kind: "date", mode: "create-only", required: true, column: true },
    { name: "kind", labelKey: "entity.field.holiday_kind", kind: "enum", mode: "create-only", required: true, ...KINDS, column: true },
    { name: "name_en", labelKey: "entity.field.name", kind: "text", mode: "create-only", required: true, maxLength: 120, column: true },
    { name: "name_bn", labelKey: "entity.field.name_bn", kind: "text", mode: "create-only", nullable: true, maxLength: 120, column: true },
    { name: "scope_type", labelKey: "entity.field.scope_type", kind: "enum", mode: "create-only", required: true, ...SCOPES, column: true },
    { name: "scope_id", labelKey: "entity.field.scope_id", kind: "int", mode: "create-only", required: true, min: 0, column: true },
    { name: "selling_day", labelKey: "entity.field.selling_day", kind: "bool", mode: "readonly", column: true },
  ],
  filters: [
    { param: "from", kind: "date", labelKey: "entity.field.from" },
    { param: "to", kind: "date", labelKey: "entity.field.to" },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: ["ADMIN", "SUPERADMIN"],
  reasonOnUpdate: null,
  // HolidayWrite.reason is REQUIRED (ChangeReason) in the contract.
  reasonOnCreate: "reason",
});
