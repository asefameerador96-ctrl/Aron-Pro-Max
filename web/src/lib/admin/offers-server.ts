// BFF handlers of the discount-programme writes (portal-discount-web, D-DISC-WEB-01): createOffer, updateOffer, setOfferStatus. The
// browser sends the contract body (with its client request_uuid); this layer checks the session, the role and the `discounts` grant,
// validates the body again (zod, strict: no unknown member), and forwards it with the token the browser never sees. The API stays
// the authority on scope, activation rights and every state rule (403 / 404 / 409 / 412 pass through unchanged).
import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Problem } from "@/contract/types";
import { apiClient, outcome } from "@/lib/api/client";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { loadGrants } from "./modules";
import { canActivateOffer, canWriteOffers, CODE_PATTERN, type OfferPatch, type OfferWrite } from "./offers";
import { toFieldErrors } from "@/components/admin/crud/validation";

const UUID = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
const DAY = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const REASON = z.string().trim().refine((v) => Array.from(v).length >= 10 && Array.from(v).length <= 500, { message: "reason" });
const TYPE = z.enum(["pct_discount", "amount_per_unit", "free_qty", "drp_slide"]);
const int = (min: number, max = Number.MAX_SAFE_INTEGER) => z.number().int().min(min).max(max);
const nullable = <T extends z.ZodType>(s: T) => s.nullable();
const QUALIFYING = z.array(z.object({ product_level: z.enum(["category", "brand", "variant", "sku"]), product_id: int(1) }).strict()).max(100);
const SCOPE = z.array(z.object({ node_type: z.enum(["wing", "division", "territory", "zone", "route", "channel", "sub_channel"]), node_id: int(1) }).strict()).max(200);
const RULE = {
  offer_type: TYPE,
  level: z.enum(["line", "memo"]),
  title_en: z.string().min(1).max(200),
  title_bn: nullable(z.string().max(200)),
  valid_from: DAY,
  valid_to: DAY,
  threshold_qty_base: nullable(int(1, 2_147_483_647)),
  discount_bp: nullable(int(1, 10_000)),
  discount_mtk_per_unit: nullable(int(1)),
  reward_sku_id: nullable(int(1)),
  reward_qty_base: nullable(int(1, 2_147_483_647)),
  qualifying: QUALIFYING,
  scope: SCOPE,
  stacking: z.enum(["exclusive", "stackable"]),
  change_reason: REASON,
  request_uuid: UUID,
};
const CreateBody = z.object({ code: z.string().regex(CODE_PATTERN), group_code: z.string().max(40), ...RULE }).strict();
const PatchBody = z.object({ version: int(1, 9_999_999_999), ...Object.fromEntries(Object.entries(RULE).map(([k, v]) => [k, v.optional()])) as { [K in keyof typeof RULE]: z.ZodOptional<(typeof RULE)[K]> }, change_reason: REASON, request_uuid: UUID }).strict();
const StatusBody = z.object({ request_uuid: UUID, to_status: z.enum(["active", "inactive"]), reason: REASON }).strict();

const fail = (r: { status: number; problem: unknown }): NextResponse => NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
const invalid = (e: z.ZodError): NextResponse => problemResponse(400, "ERR_VALIDATION", { errors: toFieldErrors(e, "") });
const ID = /^[0-9]{1,15}$/;

async function gate(req: NextRequest) {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return { res: auth };
  const role = auth.session.user.role;
  const grants = await loadGrants(auth.session.at, role);
  // A read-only grant (or any other role) never reaches the API from here; the API would refuse it too (403).
  if (!canWriteOffers(role, grants)) return { res: auth.finish(problemResponse(403, "ERR_FORBIDDEN")) };
  return { auth, role };
}

/** POST /api/bff/admin/offers: createOffer (a draft, version 1). */
export async function handleOfferCreate(req: NextRequest): Promise<NextResponse> {
  const g = await gate(req);
  if (g.res) return g.res;
  const parsed = CreateBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  const r = await outcome(apiClient(g.auth!.session.at).POST("/v1/admin/offers", { body: parsed.data as OfferWrite }));
  return g.auth!.finish(r.ok ? NextResponse.json(r.data, { status: r.status }) : fail(r));
}

/** PATCH /api/bff/admin/offers/{id}: updateOffer (a new version while draft); `version` is the row version for If-Match. */
export async function handleOfferUpdate(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req);
  if (g.res) return g.res;
  const parsed = PatchBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  const { version, ...patch } = parsed.data;
  const r = await outcome(apiClient(g.auth!.session.at, { "If-Match": `"${version}"` }).PATCH("/v1/admin/offers/{id}", { params: { path: { id: Number(id) }, header: { "If-Match": `"${version}"` } }, body: patch as OfferPatch }));
  return g.auth!.finish(r.ok ? NextResponse.json(r.data) : fail(r));
}

/** POST /api/bff/admin/offers/{id}/status: setOfferStatus. draft -> active is a Super Admin act; the API refuses it for others too. */
export async function handleOfferStatus(req: NextRequest, id: string): Promise<NextResponse> {
  if (!ID.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const g = await gate(req);
  if (g.res) return g.res;
  const parsed = StatusBody.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return g.auth!.finish(invalid(parsed.error));
  if (parsed.data.to_status === "active" && !canActivateOffer(g.role)) return g.auth!.finish(problemResponse(403, "ERR_FORBIDDEN"));
  const r = await outcome(apiClient(g.auth!.session.at).POST("/v1/admin/offers/{id}/status", { params: { path: { id: Number(id) } }, body: parsed.data }));
  return g.auth!.finish(r.ok ? NextResponse.json(r.data) : fail(r));
}
