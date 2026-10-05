import { NextResponse, type NextRequest } from "next/server";
import { publicUrl, safeNext } from "@/lib/api/origin";
import { RT_COOKIE } from "@/lib/auth/cookies";
import { clearAuthCookies, refreshSession, setAuthCookies } from "@/lib/auth/service";

/** Pages send an expiring session here (GET navigation, SameSite=Strict cookies): rotate the cookies, then go back. */
export async function GET(req: NextRequest) {
  const next = safeNext(req.nextUrl.searchParams.get("next"));
  const toLogin = () => NextResponse.redirect(publicUrl(req, `/login?next=${encodeURIComponent(next)}`));
  const rt = req.cookies.get(RT_COOKIE)?.value;
  if (!rt) return toLogin();
  const r = await refreshSession(rt);
  if (!r.ok) {
    const res = toLogin();
    clearAuthCookies(res);
    return res;
  }
  const res = NextResponse.redirect(publicUrl(req, next));
  setAuthCookies(res, r.data);
  return res;
}
