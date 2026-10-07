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

// Only the classification lists live here (F-ADM-011). The reason-code tables and task types (F-ADM-060, F-ADM-021) are
// edited on /admin/code-lists and the QC fault types on /admin/qc-faults (web-config lane), so no list has two editors.
export const CODE_LISTS: readonly CodeListMeta[] = [L("channel", "outlets"), L("sub_channel", "outlets"), L("geo_class", "outlets")];

export function codeListByKey(key: string): CodeListMeta | undefined {
  return CODE_LISTS.find((l) => l.key === key);
}
