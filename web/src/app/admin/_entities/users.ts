// Users (F-ADM-007): create, edit, disable (status), and the credential actions. Disabling keeps the upload grant on the
// server side (contract), so pending rows still upload.
import { defineAction, defineEntity } from "@/components/admin/crud/meta";
import type { CredentialActionRequest, User, UserPatch, UserWrite } from "@/contract/types";
import { ADMIN_PORTAL_ROLES, ALL_ROLES } from "@/lib/auth/roles";
import type { MessageKey } from "@/lib/i18n";

const ROLE_KEYS = Object.fromEntries(ALL_ROLES.map((r) => [r, `role.${r}` as MessageKey]));
const ZONE_REF = { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] } as const;
const CRED_ROLES = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const;
const RESULT = ["temporary_password", "temporary_password_expires_at"] as const;

const credential = (action: CredentialActionRequest["action"], labelKey: MessageKey, showPassword: boolean) =>
  defineAction<CredentialActionRequest>({
    key: action,
    labelKey,
    path: "/v1/admin/users/{id}/credentials",
    fields: [],
    fixed: { action },
    reasonMember: "reason",
    writeRoles: CRED_ROLES,
    resultFields: showPassword ? RESULT : undefined,
    when: action === "unlock" ? undefined : (row) => row.status === "active",
  });

export const users = defineEntity<User, UserWrite, UserPatch>({
  slug: "users",
  group: "people",
  labelKey: "entity.users",
  singularKey: "entity.users.singular",
  api: { collection: "/v1/admin/users", item: "/v1/admin/users/{id}", get: "/v1/admin/users/{id}" },
  auditEntity: "user",
  idField: "id",
  fields: [
    { name: "id", labelKey: "entity.field.id", kind: "int", mode: "readonly", column: true },
    { name: "username", labelKey: "entity.field.username", kind: "text", mode: "create-only", required: true, maxLength: 40, pattern: "^[A-Za-z][A-Za-z0-9._-]{2,39}$", column: true },
    { name: "full_name", labelKey: "entity.field.full_name", kind: "text", required: true, maxLength: 120, column: true },
    { name: "role", labelKey: "entity.field.role", kind: "enum", required: true, options: ALL_ROLES, optionKeys: ROLE_KEYS, column: true },
    { name: "designation", labelKey: "entity.field.designation", kind: "text", nullable: true, maxLength: 60, column: true },
    { name: "employee_code", labelKey: "entity.field.employee_code", kind: "text", nullable: true, maxLength: 40, column: true },
    { name: "phone", labelKey: "entity.field.phone", kind: "text", nullable: true, maxLength: 11, pattern: "^01[3-9]\\d{8}$", normalizeDigits: true },
    { name: "email", labelKey: "entity.field.email", kind: "text", nullable: true, maxLength: 120, pattern: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$" },
    { name: "locale", labelKey: "entity.field.locale", kind: "enum", required: true, options: ["bn", "en"], optionKeys: { bn: "common.language.bn", en: "common.language.en" } },
    { name: "home_zone_id", labelKey: "entity.field.home_zone", kind: "ref", nullable: true, min: 1, ref: ZONE_REF },
    { name: "pilot", labelKey: "entity.field.pilot", kind: "bool" },
    {
      name: "status",
      labelKey: "entity.field.status",
      kind: "enum",
      mode: "update-only",
      options: ["active", "disabled"],
      optionKeys: { active: "entity.status.active", disabled: "entity.status.disabled" },
      column: true,
    },
    { name: "last_login_at", labelKey: "entity.field.last_login_at", kind: "timestamp", mode: "readonly" },
  ],
  filters: [
    { param: "q", kind: "search", labelKey: "common.search" },
    { param: "role", kind: "enum", labelKey: "entity.field.role", options: ALL_ROLES, optionKeys: ROLE_KEYS },
    { param: "zone_id", kind: "ref", labelKey: "entity.field.zone_id", ref: ZONE_REF },
    // The list parameter is the contract's ActiveStatus (active | inactive); "inactive" is how a disabled user is filtered.
    { param: "status", kind: "enum", labelKey: "entity.field.status", options: ["active", "inactive"], optionKeys: { active: "entity.status.active", inactive: "entity.status.disabled" } },
  ],
  readRoles: ADMIN_PORTAL_ROLES,
  writeRoles: ["ADMIN", "SUPERADMIN"],
  // docs/24 s8.5: only SUPERADMIN writes ADMIN-role users (and so creates or promotes admins).
  restrictedValues: [{ field: "role", values: ["ADMIN", "SUPERADMIN"], unlessRoles: ["SUPERADMIN"] }],
  reasonOnUpdate: "change_reason",
  // REQUEST: docs/requests/web-admin-create-reason.md (UserWrite has no reason member).
  reasonOnCreate: null,
  // Create returns { user, temporary_password, temporary_password_expires_at }; the password is shown once.
  createResult: { rowKey: "user", show: RESULT },
  links: [{ key: "scope", labelKey: "action.user_scope", href: (id) => `/admin/user-scope/${id}`, roles: ["ADMIN", "SUPERADMIN"] }],
  actions: [credential("reset_password", "action.reset_password", true), credential("unlock", "action.unlock", false), credential("force_logout", "action.force_logout", false), credential("reset_mfa", "action.reset_mfa", false)],
});
