// Feedback inbox (F-ADM-028): TSO feedback listed by category and status; the status is set with a mandatory reason.
import { defineAction, defineEntity } from "@/components/admin/crud/meta";
import type { Feedback, FeedbackStatusWrite } from "@/contract/types";

const STATUS = {
  options: ["new", "in_progress", "resolved", "closed"],
  optionKeys: { new: "feedback.status.new", in_progress: "feedback.status.in_progress", resolved: "feedback.status.resolved", closed: "feedback.status.closed" },
} as const;

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
    { name: "category_code", labelKey: "entity.field.category", kind: "text", mode: "readonly", column: true },
    { name: "title", labelKey: "entity.field.title", kind: "text", mode: "readonly", column: true },
    { name: "description", labelKey: "entity.field.description", kind: "text", mode: "readonly", column: true },
    { name: "status", labelKey: "entity.field.status", kind: "enum", mode: "readonly", ...STATUS, column: true },
    { name: "user_id", labelKey: "entity.field.user", kind: "int", mode: "readonly", column: true },
    { name: "created_at", labelKey: "entity.field.created_at", kind: "timestamp", mode: "readonly", column: true },
  ],
  // REQUEST: docs/requests/web-admin-feedback-filters.md (listFeedback has no category or status parameter yet).
  filters: [],
  readRoles: ["ADMIN", "SUPERADMIN", "SUPPORT"],
  writeRoles: ["ADMIN", "SUPERADMIN"],
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
    }),
  ],
});
