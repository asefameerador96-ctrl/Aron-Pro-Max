// Business code lists edited as whole lists through PUT /v1/admin/code-lists/{list_key} (classification F-ADM-011, task types
// F-ADM-021, QC fault types F-ADM-023, reason-code tables F-ADM-060). Codes are immutable and retired by valid_to, never deleted.
import type { CodeListKey } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import type { MasterGroup } from "@/components/admin/crud/meta";

export interface AttrSpec {
  key: string;
  labelKey: MessageKey;
  /** Fixed choices; absent = free text. */
  options?: readonly string[];
}

export interface CodeListMeta {
  key: CodeListKey;
  labelKey: MessageKey;
  group: MasterGroup;
  attrs?: readonly AttrSpec[];
}

const L = (key: CodeListKey, group: MasterGroup, attrs?: readonly AttrSpec[]): CodeListMeta => ({ key, labelKey: `codelist.${key}` as MessageKey, group, attrs });

export const CODE_LISTS: readonly CodeListMeta[] = [
  L("channel", "outlets"),
  L("sub_channel", "outlets"),
  L("geo_class", "outlets"),
  // ASSUMED attrs.roles (comma-separated role codes allowed to assign the type): docs/status/web-admin.md, to be confirmed.
  L("task_type", "lists", [{ key: "roles", labelKey: "codelist.attr.roles" }]),
  // 11 codes; group MFC or MKT, applies_to app or web (F-ADM-023).
  L("qc_fault_type", "lists", [
    { key: "group", labelKey: "codelist.attr.group", options: ["MFC", "MKT"] },
    { key: "applies_to", labelKey: "codelist.attr.applies_to", options: ["app", "web"] },
  ]),
  L("force_reason", "lists"),
  L("edit_reason", "lists"),
  L("void_reason", "lists"),
  L("visit_outcome", "lists"),
  L("skip_reason", "lists"),
  L("day_exception_reason", "lists"),
  L("stock_variance_reason", "lists"),
  L("leave_type", "lists"),
  L("feedback_category", "lists"),
  L("payment_mode", "lists"),
  L("outlet_close_reason", "lists"),
  L("submit_void_reason", "lists"),
];

export function codeListByKey(key: string): CodeListMeta | undefined {
  return CODE_LISTS.find((l) => l.key === key);
}
