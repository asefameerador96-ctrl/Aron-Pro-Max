import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { clientIpHeaders } from "@/lib/api/client-ip";
import { completeLogin } from "@/lib/auth/complete-login";

const Body = z.object({ username: z.string().min(1).max(40), password: z.string().min(1).max(128), remember: z.boolean().optional() }).strict();

export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const parsed = Body.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return problemResponse(400, "ERR_VALIDATION");

  const r = await outcome(apiClient(undefined, clientIpHeaders((n) => req.headers.get(n), crypto.randomUUID())).POST("/v1/auth/login", { body: { username: parsed.data.username.trim().toLowerCase(), password: parsed.data.password, client: "web", device_uuid: null } }));
  if (!r.ok) return NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
  return completeLogin(r.response, r.data, parsed.data.remember === true);
}
