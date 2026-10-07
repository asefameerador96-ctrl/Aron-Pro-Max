// BFF write of a whole code list. Codes are immutable and never deleted (contract): the BFF refuses a list that drops a
// saved code, so a stale browser tab cannot silently remove one.
import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { CodeList } from "@/contract/types";
import { codeListByKey } from "@/app/admin/_codelists/registry";
import { apiClient, outcome } from "@/lib/api/client";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { reasonSchema, toFieldErrors } from "./validation";

export const CODELIST_WRITE_ROLES = ["ADMIN", "SUPERADMIN"] as const;

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const Item = z
  .object({
    code: z.string().regex(/^[a-z][a-z0-9_]{1,40}$/),
    label_en: z.string().trim().min(1).max(120),
    label_bn: z.string().trim().max(120).nullable().optional(),
    sort: z.number().int(),
    valid_from: date.optional(),
    valid_to: date.nullable().optional(),
    attrs: z.record(z.string(), z.union([z.string().max(120), z.number(), z.boolean(), z.null()])).optional(),
  })
  .strict();
const Body = z.object({ items: z.array(Item).min(1).max(100), reason: z.unknown() }).strict();

export async function handleCodeListPut(req: NextRequest, key: string): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!(CODELIST_WRITE_ROLES as readonly string[]).includes(auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  const meta = codeListByKey(key);
  if (!meta) return problemResponse(404, "ERR_NOT_FOUND");
  const raw = await req.json().catch(() => null);
  const body = Body.safeParse(raw);
  const reason = reasonSchema.safeParse((raw as { reason?: unknown } | null)?.reason);
  const errors = [...(body.success ? [] : toFieldErrors(body.error, "")), ...(reason.success ? [] : toFieldErrors(reason.error, "/reason"))];
  if (!body.success || !reason.success) return problemResponse(400, "ERR_VALIDATION", { errors });

  const codes = body.data.items.map((i) => i.code);
  if (new Set(codes).size !== codes.length) return problemResponse(400, "ERR_VALIDATION", { errors: [{ pointer: "/items", code: "duplicate" }] });

  const current = await outcome(apiClient(auth.session.at).GET("/v1/admin/code-lists"));
  if (!current.ok) return auth.finish(NextResponse.json(current.problem, { status: current.status, headers: { "Content-Type": "application/problem+json" } }));
  const saved = current.data.lists.find((l) => l.list_key === key)?.items ?? [];
  const missing = saved.filter((s) => !codes.includes(s.code));
  if (missing.length) return problemResponse(400, "ERR_VALIDATION", { errors: missing.map(() => ({ pointer: "/items", code: "code_removed" })) });

  const r = await outcome<CodeList>(apiClient(auth.session.at).PUT("/v1/admin/code-lists/{list_key}", { params: { path: { list_key: meta.key } }, body: { items: body.data.items.map((i) => ({ ...i, label_bn: i.label_bn ?? null })), change_reason: reason.data } }));
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json({ list: r.data }));
}
