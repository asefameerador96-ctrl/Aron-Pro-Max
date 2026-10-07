// BFF handlers of the tutorial pages (F-ADM-026). Server only: the access token never leaves it, and the SAS upload URL is
// returned to the caller once and never logged.
import { NextResponse, type NextRequest } from "next/server";
import type { Problem } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest, type RawRequest } from "@/lib/api/raw";
import { checkContentWrite, checkRubricWrite, checkSurveyWrite } from "@/lib/admin/definitions";
import { checkAssetRequest, checkTutorialWrite, type FieldIssue } from "@/lib/admin/tutorials";
import { hasRole } from "@/lib/auth/roles";

const WRITE = ["ADMIN", "SUPERADMIN"] as const;
export type Plan = { issues: FieldIssue[] } | { call: Omit<RawRequest, "token"> };

export async function run(req: NextRequest, plan: (input: unknown) => Plan): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!hasRole(auth.session.user.role, WRITE)) return auth.finish(problemResponse(403, "ERR_FORBIDDEN"));
  const input = await req.json().catch(() => undefined);
  if (input === undefined) return auth.finish(problemResponse(400, "ERR_MALFORMED_JSON"));
  const p = plan(input);
  if ("issues" in p) return auth.finish(problemResponse(400, "ERR_VALIDATION", { errors: p.issues }));
  const r = await rawRequest<unknown>({ ...p.call, token: auth.session.at });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json({ data: r.data ?? null }, { status: r.status === 204 ? 200 : r.status }));
}

/** POST /api/bff/admin/assets: a write-only upload URL for a tutorial file. */
export const handleAssetUpload = (req: NextRequest) =>
  run(req, (input) => {
    const c = checkAssetRequest(input);
    return c.body ? { call: { method: "POST", path: "/v1/admin/assets", body: c.body } } : { issues: c.issues };
  });

/** POST /api/bff/admin/tutorials. */
export const handleTutorialCreate = (req: NextRequest) =>
  run(req, (input) => {
    const c = checkTutorialWrite(input);
    return c.body ? { call: { method: "POST", path: "/v1/admin/tutorials", body: c.body } } : { issues: c.issues };
  });

/** PATCH /api/bff/admin/tutorials/{id} with the row `version` as If-Match. */
export const handleTutorialUpdate = (req: NextRequest, id: string) =>
  run(req, (input) => {
    const o = (typeof input === "object" && input !== null && !Array.isArray(input) ? input : {}) as Record<string, unknown>;
    const { version, ...rest } = o;
    const issues: FieldIssue[] = [];
    if (!/^[1-9][0-9]{0,14}$/.test(id)) issues.push({ pointer: "/id", code: "invalid" });
    if (typeof version !== "number" || !Number.isInteger(version) || version < 1 || version > 9_999_999_999) issues.push({ pointer: "/version", code: "invalid" });
    const c = checkTutorialWrite(rest);
    issues.push(...c.issues);
    return c.body && !issues.length ? { call: { method: "PATCH", path: `/v1/admin/tutorials/${id}`, body: c.body, ifMatch: `"${version}"` } } : { issues };
  });

const DEF_KINDS = { surveys: checkSurveyWrite, rubrics: checkRubricWrite, content: checkContentWrite } as const;
export type DefKind = keyof typeof DEF_KINDS;
export const isDefKind = (k: string): k is DefKind => Object.prototype.hasOwnProperty.call(DEF_KINDS, k);

/** POST /api/bff/admin/defs/{surveys|rubrics|content}: create a definition (version 1). */
export const handleDefCreate = (req: NextRequest, kind: string) =>
  isDefKind(kind)
    ? run(req, (input) => {
        const c = DEF_KINDS[kind](input);
        return c.body ? { call: { method: "POST", path: `/v1/admin/${kind}`, body: c.body } } : { issues: c.issues };
      })
    : Promise.resolve(problemResponse(404, "ERR_NOT_FOUND"));

/** PATCH /api/bff/admin/defs/{kind}/{id}: publish a new version, with the row `version` as If-Match. */
export const handleDefUpdate = (req: NextRequest, kind: string, id: string) =>
  isDefKind(kind)
    ? run(req, (input) => {
        const o = (typeof input === "object" && input !== null && !Array.isArray(input) ? input : {}) as Record<string, unknown>;
        const { version, ...rest } = o;
        const issues: FieldIssue[] = [];
        if (!/^[1-9][0-9]{0,14}$/.test(id)) issues.push({ pointer: "/id", code: "invalid" });
        if (typeof version !== "number" || !Number.isInteger(version) || version < 1 || version > 9_999_999_999) issues.push({ pointer: "/version", code: "invalid" });
        const c = DEF_KINDS[kind](rest);
        issues.push(...c.issues);
        return c.body && !issues.length ? { call: { method: "PATCH", path: `/v1/admin/${kind}/${id}`, body: c.body, ifMatch: `"${version}"` } } : { issues };
      })
    : Promise.resolve(problemResponse(404, "ERR_NOT_FOUND"));
