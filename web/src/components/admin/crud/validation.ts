// Server-side validation derived from entity metadata. Strict like the API: unknown members are refused.
import { z } from "zod";
import type { FieldError } from "./types";
import { isWritable, type AnyEntity, type AnyField } from "./meta";

export const REASON_MIN = 10; // ChangeReason in the contract
export const REASON_MAX = 500;

/** JSON Schema minLength/maxLength count Unicode code points, not UTF-16 units (an emoji is 1, not 2). */
export const codePoints = (s: string): number => Array.from(s).length;

function fieldSchema(f: AnyField): z.ZodType {
  if (f.kind === "bool") {
    const b = z.preprocess((v) => (v === "true" ? true : v === "false" ? false : v), z.boolean());
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), b.nullable()) : b;
  }
  if (f.kind === "int" || f.kind === "ref") {
    const base = z.coerce.number().int().min(f.min ?? 0);
    return f.nullable ? z.preprocess((v) => (v === "" || v === null ? null : v), base.nullable()) : base;
  }
  if (f.kind === "enum") {
    const e = z.enum((f.options ?? []) as [string, ...string[]]);
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), e.nullable()) : e;
  }
  let s: z.ZodType<string> = z.string().trim();
  const max = f.maxLength;
  if (max) s = s.refine((v) => codePoints(v) <= max, { message: "too_big" });
  if (f.required && !f.nullable) s = s.refine((v) => v.length >= 1, { message: "too_small" });
  if (f.pattern) {
    const re = new RegExp(f.pattern);
    s = s.refine((v) => v === "" || re.test(v), { message: "invalid" });
  }
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

export const reasonSchema = z
  .string()
  .trim()
  .refine((v) => codePoints(v) >= REASON_MIN, { message: "too_short" })
  .refine((v) => codePoints(v) <= REASON_MAX, { message: "too_long" });

export function toFieldErrors(err: z.ZodError, prefix: string): FieldError[] {
  return err.issues.map((i) => ({
    pointer: `${prefix}/${i.path.join("/")}`.replace(/\/$/, ""),
    code:
      i.code === "unrecognized_keys" ? "unknown_member"
      : i.code === "too_small" || i.message === "too_small" || i.message === "too_short" ? "too_short"
      : i.code === "too_big" || i.message === "too_big" || i.message === "too_long" ? "too_long"
      : "invalid",
  }));
}
