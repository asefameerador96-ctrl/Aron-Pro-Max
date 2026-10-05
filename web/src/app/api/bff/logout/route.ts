import { NextResponse, type NextRequest } from "next/server";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { clearAuthCookies } from "@/lib/auth/service";
import { readSession } from "@/lib/auth/session";

export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const session = readSession(req.cookies.get(SESSION_COOKIE)?.value);
  const rt = req.cookies.get(RT_COOKIE)?.value;
  if (session) {
    // Best effort: the local session ends whatever the API answers.
    await outcome(apiClient(session.at, rt ? { Cookie: `aron_rt=${rt}` } : undefined).POST("/v1/auth/logout", { body: { scope: "session", refresh_token: null } }));
  }
  const res = new NextResponse(null, { status: 204 });
  clearAuthCookies(res);
  return res;
}
