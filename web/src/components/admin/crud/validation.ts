// Server-side validation derived from entity metadata. Strict like the API: unknown members are refused.
import { z } from "zod";
import { businessDate } from "@/lib/i18n";
import type { FieldError } from "./types";
import { isWritable, type AnyEntity, type AnyField } from "./meta";

export const REASON_MIN = 10; // ChangeReason in the contract
export const REASON_MAX = 500;

/** JSON Schema minLength/maxLength count Unicode code points, not UTF-16 units (an emoji is 1, not 2). */
export const codePoints = (s: string): number => Array.from(s).length;

const BN_DIGITS = "০১২৩৪৫৬৭৮৯";
/** Bengali digits to ASCII (numbers, dates and flagged text are typed in either script). */
export const asciiDigits = (v: unknown): unknown => (typeof v === "string" ? v.replace(/[০-৯]/g, (d) => String(BN_DIGITS.indexOf(d))) : v);

/** A real calendar date: 2026-02-30 and 2026-13-45 are not dates. */
export function isRealDate(v: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v);
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

function fieldSchema(f: AnyField): z.ZodType {
  if (f.kind === "bool") {
    const b = z.preprocess((v) => (v === "true" ? true : v === "false" ? false : v), z.boolean());
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), b.nullable()) : b;
  }
  if (f.kind === "number") {
    let n = z.preprocess((v) => (typeof v === "string" ? (v.trim() === "" ? Number.NaN : asciiDigits(v.trim())) : v), z.coerce.number().refine((v) => Number.isFinite(v), "invalid"));
    if (f.min !== undefined || f.max !== undefined) {
      const lo = f.min ?? -Infinity;
      const hi = f.max ?? Infinity;
      n = z.preprocess((v) => (typeof v === "string" ? (v.trim() === "" ? Number.NaN : asciiDigits(v.trim())) : v), z.coerce.number().refine((v) => Number.isFinite(v) && v >= lo && v <= hi, "invalid"));
    }
    return f.nullable ? z.preprocess((v) => (v === "" || v === null ? null : v), n.nullable()) : n;
  }
  if (f.kind === "date") {
    let d: z.ZodType<string> = z.preprocess(asciiDigits, z.string().refine(isRealDate, "invalid")) as unknown as z.ZodType<string>;
    if (f.futureOnly) d = d.refine((v) => v >= businessDate(), "past");
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), d.nullable()) : d;
  }
  if (f.kind === "mask") {
    const bits = f.maskBits?.length ?? 7;
    return z.preprocess(asciiDigits, z.coerce.number().int().min(1).max(2 ** bits - 1));
  }
  if (f.kind === "int" || f.kind === "ref") {
    // Only a non-empty string or a number is a number: "", null, true and [] must not coerce to 0 or 1.
    const strict = (v: unknown) => (typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? asciiDigits(v.trim()) : Number.NaN);
    let num = z.coerce.number().int();
    if (f.min !== undefined) num = num.min(f.min);
    if (f.max !== undefined) num = num.max(f.max);
    const base = z.preprocess(strict, num) as unknown as z.ZodNumber;
    return f.nullable ? z.preprocess((v) => (v === "" || v === null ? null : v), base.nullable()) : base;
  }
  if (f.kind === "enum") {
    const e = z.enum((f.options ?? []) as [string, ...string[]]);
    return f.nullable ? z.preprocess((v) => (v === "" ? null : v), e.nullable()) : e;
  }
  const ascii = (v: unknown) => (f.normalizeDigits ? asciiDigits(v) : v);
  let s: z.ZodType<string> = z.preprocess(ascii, z.string().trim()) as unknown as z.ZodType<string>;
  const max = f.maxLength;
  if (max) s = s.refine((v) => codePoints(v) <= max, { message: "too_big" });
  if (f.required && !f.nullable) s = s.refine((v) => v.length >= 1, { message: "too_small" });
  const minLen = f.minLength;
  if (minLen) s = s.refine((v) => codePoints(v) >= minLen, { message: "too_small" });
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
      : i.message === "past" ? "past"
      : i.code === "too_small" || i.message === "too_small" || i.message === "too_short" ? "too_short"
      : i.code === "too_big" || i.message === "too_big" || i.message === "too_long" ? "too_long"
      : "invalid",
  }));
}
