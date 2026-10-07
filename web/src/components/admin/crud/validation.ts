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
  if (f.kind === "date") {
    const d = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "invalid");
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), d.nullable()) : d;
  }
  if (f.kind === "mask") {
    const bits = f.maskBits?.length ?? 7;
    return z.coerce.number().int().min(1).max(2 ** bits - 1);
  }
  if (f.kind === "int" || f.kind === "ref") {
    const base = z.coerce.number().int().min(f.min ?? 0);
    return f.nullable ? z.preprocess((v) => (v === "" || v === null ? null : v), base.nullable()) : base;
  }
  if (f.kind === "enum") {
    const e = z.enum((f.options ?? []) as [string, ...string[]]);
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), e.nullable()) : e;
  }
  const BN = "০১২৩৪৫৬৭৮৯";
  const ascii = (v: unknown) => (f.normalizeDigits && typeof v === "string" ? v.replace(/[০-৯]/g, (d) => String(BN.indexOf(d))) : v);
  let s: z.ZodType<string> = z.preprocess(ascii, z.string().trim()) as unknown as z.ZodType<string>;
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

/** Body schema of a row action (all fields required unless `required` is false... every action input is required by default). */
export function actionSchema(fields: readonly AnyField[]) {
  const shape: Record<string, z.ZodType> = {};
  for (const f of fields) shape[f.name] = f.required === false ? fieldSchema(f).optional() : fieldSchema(f);
  return z.object(shape).strict();
}

export function reasonSchemaFor(max = REASON_MAX) {
  return z
    .string()
    .trim()
    .refine((v) => codePoints(v) >= REASON_MIN, { message: "too_short" })
    .refine((v) => codePoints(v) <= max, { message: "too_long" });
}
export const reasonSchema = reasonSchemaFor();

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
