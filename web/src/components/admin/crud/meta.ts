// Entity metadata: ONE entry per master-data table produces its list, filter, create and edit pages and the BFF writes.
// Field names are typed against the generated contract schemas (Row, Write, Patch), so a contract rename that touches a
// field the portal edits fails `tsc` here, in the same build (docs/24 s13.2).
import type { ApiPath, Role } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import type { RoleList } from "@/lib/auth/roles";

export type MasterGroup = "geography" | "routes" | "products" | "people" | "outlets" | "lists";
export const MASTER_GROUPS: readonly MasterGroup[] = ["geography", "routes", "products", "people", "outlets", "lists"];

type Name<T> = Extract<keyof T, string>;

/** A reference to another table: the form shows a select filled from that table's list operation. */
export interface RefMeta {
  /** List path of the referenced table (typed against the contract). */
  path: ApiPath;
  /** Path parameters of that list path, e.g. { level: "zone" }. */
  params?: Record<string, string>;
  /** Member holding the value (default "id"). */
  value?: string;
  /** Members shown, joined with " · " (default ["name"]). */
  label?: readonly string[];
}

interface FieldBase {
  labelKey: MessageKey;
  /** `ref` stores an integer id and shows a select (see `ref`). `bool` is a yes/no select. */
  kind: "text" | "int" | "enum" | "timestamp" | "ref" | "bool";
  ref?: RefMeta;
  /** Regular expression of a valid value (the contract's `pattern`). */
  pattern?: string;
  /** Required when creating. */
  required?: boolean;
  /** Empty input is sent as null. */
  nullable?: boolean;
  maxLength?: number;
  min?: number;
  options?: readonly string[];
  optionKeys?: Record<string, MessageKey>;
  /** Show as a list column. */
  column?: boolean;
}

export type FieldMeta<Row, Write, Patch> =
  | (FieldBase & { mode?: "rw"; name: Name<Row> & Name<Write> & Name<Patch> })
  | (FieldBase & { mode: "create-only"; name: Name<Row> & Name<Write> })
  | (FieldBase & { mode: "update-only"; name: Name<Row> & Name<Patch> })
  | (FieldBase & { mode: "readonly"; name: Name<Row> });

export interface FilterMeta {
  /** Query parameter of the list operation (must exist in the contract; typed by the entity's list path). */
  param: string;
  kind: "int" | "enum" | "search" | "ref";
  ref?: RefMeta;
  labelKey: MessageKey;
  options?: readonly string[];
  optionKeys?: Record<string, MessageKey>;
}

export interface EntityMeta<Row, Write, Patch> {
  /** URL slug: /admin/<slug>. */
  slug: string;
  /** Section of the master-data hub (/admin/master-data) this table is listed under. */
  group: MasterGroup;
  labelKey: MessageKey;
  singularKey: MessageKey;
  /** Values for `{placeholders}` of the paths other than `{id}`, e.g. { level: "zone" } for /v1/admin/geo/{level}. */
  params?: Record<string, string>;
  api: {
    collection: ApiPath;
    /** Item path with an `{id}` placeholder (PATCH). */
    item: ApiPath;
    /** GET one row, when the contract has it; otherwise the row is read through the list operation. */
    get?: ApiPath;
  };
  /** `entity` value of this table's rows in /v1/admin/audit. */
  auditEntity: string;
  idField: Name<Row>;
  fields: readonly FieldMeta<Row, Write, Patch>[];
  filters: readonly FilterMeta[];
  readRoles: RoleList;
  writeRoles: RoleList;
  /**
   * Body member that carries the mandatory reason on UPDATE (the contract's `change_reason`).
   * The portal always demands a reason; this says where the contract takes it.
   */
  reasonOnUpdate: Name<Patch>;
  /**
   * Body member that carries the reason on CREATE, or null while the contract has none.
   * The reason is still required in the UI and by the BFF; with null it cannot be stored yet (a docs/requests/ item).
   */
  reasonOnCreate: Name<Write> | null;
}

/** Type-checks an entity against the contract schemas and returns it unchanged. */
export function defineEntity<Row, Write, Patch>(meta: EntityMeta<Row, Write, Patch>): AnyEntity {
  return meta as unknown as AnyEntity;
}

/** The erased form the generic engine works with (rows are plain records at runtime). */
export type AnyEntity = EntityMeta<Record<string, unknown>, Record<string, unknown>, Record<string, unknown>>;
export type AnyField = AnyEntity["fields"][number];

export function isWritable(f: AnyField, mode: "create" | "update"): boolean {
  const m = f.mode ?? "rw";
  if (m === "readonly") return false;
  if (m === "rw") return true;
  return mode === "create" ? m === "create-only" : m === "update-only";
}

/** Fill `{name}` placeholders of a contract path. */
export function resolvePath(template: string, params: Record<string, string | number> = {}): string {
  return template.replace(/\{(\w+)\}/g, (_m, k: string) => encodeURIComponent(String(params[k] ?? `{${k}}`)));
}

export function canRead(meta: AnyEntity, role: Role): boolean {
  return meta.readRoles.includes(role);
}
export function canWrite(meta: AnyEntity, role: Role): boolean {
  return meta.writeRoles.includes(role);
}
