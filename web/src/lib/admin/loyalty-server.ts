// BFF handlers of the loyalty-programme writes (portal-loyalty-web, D-LOY-WEB-01): createProgramme, updateProgramme (If-Match),
// status change, putProgrammeEnrolments, createGift, updateGift (If-Match). The browser sends the body (with its client request_uuid /
// batch_uuid); this layer checks the session, the role and the `loyalty` grant, validates the body again (zod, strict: no unknown
// member) and forwards it with the token the browser never sees. The browser never sends scope: the API derives it from the token.
// The API stays the authority on activation rights and every state rule (403 / 404 / 409 / 412 pass through unchanged).
import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Problem } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { toFieldErrors } from "@/components/admin/crud/validation";
import { loadGrants } from "./modules";
import { canActivateProgramme, canWriteGifts, canWriteLoyalty, GIFT_CODE, PROGRAMME_CODE, PROGRAMME_KINDS, TIER_CODE } from "./loyalty";

const UUID = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
const DAY = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const DEC3 = z.string().regex(/^\d{1,13}(\.\d{1,3})?$/); // not negative: a negative target is refused here and by the API
const REASON = z.string().trim().refine((v) => Array.from(v).length >= 10 && Array.from(v).length <= 500, { message: "reason" });
const TIER = z.object({
  tier_code: z.string().regex(TIER_CODE),
  label_en: z.string().min(1).max(60),
  label_bn: z.string().max(60).nullable(),
  threshold_value: DEC3.nullable(),
  threshold_basis: z.literal("std"),
}).strict();
const CHANNEL = z.enum(["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"]);
const PROGRAMME = {
  request_uuid: UUID,
  kind: z.enum(PROGRAMME_KINDS),
  code: z.string().regex(PROGRAMME_CODE),
  name_en: z.string().min(1).max(120),
  name_bn: z.string().max(120).nullable(),
  period_label: z.string().max(40).nullable(),
  active_from: DAY,
  active_to: DAY,
  tiers: z.array(TIER).max(20),
  channels: z.array(CHANNEL).max(6),
  change_reason: REASON,
};
const ProgrammeBody = z.object(PROGRAMME).strict();
const ProgrammePatch = z.object({ version: z.number().int().min(1).max(9_999_999_999), ...PROGRAMME }).strict();
const StatusBody = z.object({ request_uuid: UUID, to_status: z.enum(["active", "inactive"]), reason: REASON }).strict();
const EnrolBody = z.object({
  batch_uuid: UUID,
  rows: z.array(z.object({
    programme_id: z.number().int().min(1),
    outlet_id: z.number().int().min(1),
    league_label: z.string().max(40).nullable().optional(),
    tier_code: z.string().regex(TIER_CODE).nullable().optional(),
    base_target: DEC3.nullable().optional(),
    valid_from: DAY,
    valid_to: DAY.nullable().optional(),
  }).strict()).min(1).max(20_000),
  change_reason: REASON,
}).strict();
const GIFT = {
  request_uuid: UUID,
  programme_id: z.number().int().min(1),
  code: z.string().regex(GIFT_CODE),
  name_en: z.string().min(1).max(120),
  name_bn: z.string().max(120).nullable(),
  points_cost: z.number().int().min(1).max(100_000).nullable(),
  tier_codes: z.array(z.string().regex(TIER_CODE)).max(10),
  status: z.enum(["active", "inactive"]),
  change_reason: REASON,
};
const GiftBody = z.object(GIFT).strict();
const GiftPatch = z.object({ version: z.number().int().min(1).max(9_999_999_999), ...GIFT }).strict();

const ID = /^[0-9]{1,15}$/;
const invalid = (e: z.ZodError): NextResponse => problemResponse(400, "ERR_VALIDATION", { errors: toFieldErrors(e, "") });
const pass = (r: { ok: true; status: number; data: unknown } | { ok: false; status: number; problem: Problem }): NextResponse =>
  r.ok ? NextResponse.json(r.data, { status: r.status }) : NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });

type Auth = Exclude<Awaited<ReturnType<typeof authenticate>>, NextResponse>;
async function gate(req: NextRequest, rule: "programme" | "gift") {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return { res: auth };
  const role = auth.session.user.role;
  const grants = await loadGrants(auth.session.at, role);
  // A read-only grant (or any other role) never reaches the API from here; the API would refuse it too (403).
  const allowed = rule === "gift" ? canWriteGifts(role) : canWriteLoyalty(role, grants);
  if (!allowed) return { res: auth.finish(problemResponse(403, "ERR_FORBIDDEN")) };
  return { auth: auth as Auth, role };
}

/** POST /api/bff/admin/loyalty: createProgramme (always a draft, version 1). */
export async function handleProgrammeCreate(req: NextRequest): Promise<NextResponse> {
  const g = await gate(req, "programme");
  if (g.res) return g.res;
  const parsed = ProgrammeBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  return g.auth!.finish(pass(await rawRequest({ method: "POST", path: "/v1/admin/programmes", token: g.auth!.session.at, body: parsed.data })));
}

/** PATCH /api/bff/admin/loyalty/{id}: updateProgramme (a new version while draft); `version` is the row version for If-Match. */
export async function handleProgrammeUpdate(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req, "programme");
  if (g.res) return g.res;
  const parsed = ProgrammePatch.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  const { version, ...body } = parsed.data;
  return g.auth!.finish(pass(await rawRequest({ method: "PATCH", path: `/v1/admin/programmes/${id}`, token: g.auth!.session.at, body, ifMatch: `"${version}"` })));
}

/** POST /api/bff/admin/loyalty/{id}/status: draft -> active is a Super Admin act; the API refuses it for others too. */
export async function handleProgrammeStatus(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req, "programme");
  if (g.res) return g.res;
  const parsed = StatusBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  if (parsed.data.to_status === "active" && !canActivateProgramme(g.role)) return g.auth!.finish(problemResponse(403, "ERR_FORBIDDEN"));
  return g.auth!.finish(pass(await rawRequest({ method: "POST", path: `/v1/admin/loyalty/programmes/${id}/status`, token: g.auth!.session.at, body: parsed.data })));
}

/** PUT /api/bff/admin/loyalty/{id}/enrolments: putProgrammeEnrolments (idempotent by batch_uuid, all or nothing). */
export async function handleEnrolments(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req, "programme");
  if (g.res) return g.res;
  const parsed = EnrolBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  if (parsed.data.rows.some((r) => r.programme_id !== Number(id))) return g.auth!.finish(problemResponse(400, "ERR_VALIDATION", { errors: [{ pointer: "/rows/programme_id", code: "invalid" }] }));
  return g.auth!.finish(pass(await rawRequest({ method: "PUT", path: `/v1/admin/programmes/${id}/enrolments`, token: g.auth!.session.at, body: parsed.data })));
}

/** POST /api/bff/admin/loyalty/gifts: createGift. Super Admin only (Q3 default). */
export async function handleGiftCreate(req: NextRequest): Promise<NextResponse> {
  const g = await gate(req, "gift");
  if (g.res) return g.res;
  const parsed = GiftBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  return g.auth!.finish(pass(await rawRequest({ method: "POST", path: "/v1/admin/gifts", token: g.auth!.session.at, body: parsed.data })));
}

/** PATCH /api/bff/admin/loyalty/gifts/{id}: updateGift (If-Match = the gift's version). */
export async function handleGiftUpdate(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req, "gift");
  if (g.res) return g.res;
  const parsed = GiftPatch.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  const { version, ...body } = parsed.data;
  return g.auth!.finish(pass(await rawRequest({ method: "PATCH", path: `/v1/admin/gifts/${id}`, token: g.auth!.session.at, body, ifMatch: `"${version}"` })));
}
