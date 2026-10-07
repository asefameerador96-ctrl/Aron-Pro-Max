import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";
import { MAX_LENGTH, checkPasswords, isValid } from "@/lib/auth/password-policy";

const body = z.object({ current_password: z.string().min(1).max(MAX_LENGTH), new_password: z.string().min(1).max(MAX_LENGTH) }).strict();

/** POST /api/bff/password: change the caller's own password (F-WEB-033). Passwords are forwarded, never logged or echoed. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const p = body.safeParse(await req.json().catch(() => null));
  if (!p.success) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const check = checkPasswords(p.data.current_password, p.data.new_password, p.data.new_password);
  if (!isValid(check)) return auth.finish(problemResponse(400, "ERR_AUTH_PASSWORD_POLICY"));
  const r = await rawRequest<undefined>({ method: "POST", path: "/v1/auth/change-password", token: auth.session.at, body: p.data });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json({ ok: true }, { headers: { "Cache-Control": "no-store" } }));
}
