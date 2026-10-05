// Server-side validation derived from entity metadata. Strict like the API: unknown members are refused.
import { z } from "zod";
import type { FieldError } from "./types";
import { isWritable, type AnyEntity, type AnyField } from "./meta";

export const REASON_MIN = 10; // ChangeReason in the contract
export const REASON_MAX = 500;

function fieldSchema(f: AnyField): z.ZodType {
  if (f.kind === "int") {
    const base = z.coerce.number().int().min(f.min ?? 0);
    return f.nullable ? z.preprocess((v) => (v === "" || v === null ? null : v), base.nullable()) : base;
  }
  if (f.kind === "enum") {
    const e = z.enum((f.options ?? []) as [string, ...string[]]);
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), e.nullable()) : e;
  }
  let s = z.string().trim();
  if (f.maxLength) s = s.max(f.maxLength);
  if (f.required && !f.nullable) s = s.min(1);
  return f.nullable ? z.preprocess((v) => (typeof v === "string" && v.trim() === "" ? null : v), s.nullable()) : s;
}

export function valuesSchema(meta: AnyEntity, mode: "create" | "update") {
  const shape: Record<string, z.ZodType> = {};
  for (const f of meta.fields) {
    if (!isWritable(f, mode)) continue;
    const s = fieldSchema(f);
    shape[f.name] = mode === "create" && f.required ? s : s.optional();
  }
  return z.object(shape).strict();
}

export const reasonSchema = z.string().trim().min(REASON_MIN).max(REASON_MAX);

export function toFieldErrors(err: z.ZodError, prefix: string): FieldError[] {
  return err.issues.map((i) => ({
    pointer: `${prefix}/${i.path.join("/")}`.replace(/\/$/, ""),
    code: i.code === "unrecognized_keys" ? "unknown_member" : i.code === "too_small" ? "too_short" : i.code === "too_big" ? "too_long" : "invalid",
  }));
}
