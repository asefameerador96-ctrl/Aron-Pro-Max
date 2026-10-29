// Feedback inbox (F-ADM-028): TSO feedback listed by category and status; the status is set with a mandatory reason.
// tso-manual W1 (FB-12, contract 1.10.0, D-TSO-MAN-36): the category by its label (code list feedback_category: suggestion and
// feedback), the author's username and zone, the photo as a thumbnail through the media read URL, the business date with a
// from/to filter, and the status action for every role the API lets triage (SUPPORT, ADMIN, SUPERADMIN: FeedbackApi
// FEEDBACK_TRIAGE).
import { defineAction, defineEntity } from "@/components/admin/crud/meta";
import type { Feedback, FeedbackStatusWrite } from "@/contract/types";

const STATUS = {
  options: ["new", "in_progress", "resolved", "closed"],
  optionKeys: { new: "feedback.status.new", in_progress: "feedback.status.in_progress", resolved: "feedback.status.resolved", closed: "feedback.status.closed" },
} as const;

const CATEGORY_REF = { path: "/v1/admin/code-lists", codeList: "feedback_category", value: "code", label: ["label_en", "label_bn"] } as const;
const ZONE_REF = { path: "/v1/admin/geo/{level}", get: "/v1/admin/geo/{level}/{id}", params: { level: "zone" }, label: ["code", "name"] } as const;
const TRIAGE = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const;

export const feedback = defineEntity<Feedback, Record<never, never>, Record<never, never>>({
  slug: "feedback",
  group: "people",
  labelKey: "entity.feedback",
  singularKey: "entity.feedback.singular",
  api: { collection: "/v1/feedback" },
  auditEntity: "feedback",
  idField: "feedback_uuid",
  idKind: "uuid",
  canCreate: false,
  fields: [
    { name: "category_code", labelKey: "entity.field.category", kind: "ref", ref: CATEGORY_REF, mode: "readonly", column: true },
    { name: "title", labelKey: "entity.field.title", kind: "text", mode: "readonly", column: true },
    { name: "photo_uuid", labelKey: "tsoman.feedback.photo", kind: "text", mode: "readonly", media: true, column: true },
    { name: "username", labelKey: "entity.field.username", kind: "text", mode: "readonly", column: true },
    { name: "zone_id", labelKey: "tsoman.feedback.zone", kind: "ref", ref: ZONE_REF, mode: "readonly", column: true },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "readonly", ...STATUS, column: true },
    { name: "business_date", labelKey: "entity.field.date", kind: "date", mode: "readonly", column: true },
    { name: "description", labelKey: "entity.field.description", kind: "text", mode: "readonly", column: true },
    { name: "user_id", labelKey: "entity.field.user", kind: "int", mode: "readonly" },
    { name: "created_at", labelKey: "entity.field.created_at", kind: "timestamp", mode: "readonly", column: true },
  ],
  filters: [
    { param: "category_code", kind: "ref", labelKey: "entity.field.category", ref: CATEGORY_REF },
    { param: "status", kind: "enum", labelKey: "entity.field.status", ...STATUS },
    { param: "from", kind: "date", labelKey: "entity.field.from" },
    { param: "to", kind: "date", labelKey: "entity.field.to" },
  ],
  readRoles: ["ADMIN", "SUPERADMIN", "SUPPORT"],
  writeRoles: TRIAGE,
  detailFields: [
    { path: "title", labelKey: "entity.field.title" },
    { path: "description", labelKey: "entity.field.description" },
  ],
  reasonOnCreate: null,
  reasonOnUpdate: null,
  actions: [
    defineAction<FeedbackStatusWrite>({
      key: "status",
      labelKey: "action.feedback_status",
      path: "/v1/feedback/{feedback_uuid}",
      method: "PATCH",
      fields: [{ name: "status", labelKey: "entity.field.status", kind: "enum", required: true, ...STATUS }],
      reasonMember: "reason",
      writeRoles: TRIAGE,
    }),
  ],
});
