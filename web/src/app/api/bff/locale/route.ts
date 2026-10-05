import { NextResponse, type NextRequest } from "next/server";
import { publicUrl, safeNext } from "@/lib/api/origin";
import { LOCALE_COOKIE, isLocale } from "@/lib/i18n/types";

/** Language switch: GET /api/bff/locale?l=en&next=/path (no JavaScript needed). */
export async function GET(req: NextRequest) {
  const l = req.nextUrl.searchParams.get("l");
  const res = NextResponse.redirect(publicUrl(req, safeNext(req.nextUrl.searchParams.get("next"))));
  if (isLocale(l)) res.cookies.set(LOCALE_COOKIE, l, { path: "/", sameSite: "lax", maxAge: 365 * 86_400 });
  return res;
}
