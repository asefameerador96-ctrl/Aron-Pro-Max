import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { loadGeoOptions, GEO_LEVELS, type GeoLevel } from "@/lib/admin/geo";
import type { DeviceOtpPage } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import type { Role } from "@/contract/types";
import { serverNowMs } from "@/lib/admin/time";
import { DeviceOtpView } from "./device-otp-view";

/** Shared body of the TSO panel (view only) and the admin page (adds re-issue). The server decides who sees the OTP value. */
export async function DeviceOtpPageContent({ searchParams, action, roles, canIssue }: { searchParams: Promise<SearchParams>; action: string; roles: readonly Role[]; canIssue: (role: Role) => boolean }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!roles.includes(session.user.role)) return <Forbidden locale={locale} />;
  const selection: Partial<Record<GeoLevel, string>> = {};
  for (const l of GEO_LEVELS) {
    const v = one(sp[l], 16);
    if (v && /^[1-9]\d{0,14}$/.test(v)) selection[l] = v;
  }
  const q = one(sp.q, 80) ?? "";
  const options = await loadGeoOptions(session.at, selection);
  let items: DeviceOtpPage["items"] | null = null;
  let nextHref: string | null = null;
  if (selection.zone) {
    const cursor = one(sp.cursor, 512);
    const r = await apiGet<DeviceOtpPage>("/v1/admin/device-otps", session.at, { zone_id: selection.zone, q: q.length >= 2 ? q : undefined, cursor, limit: 100 });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    items = r.data.items;
    nextHref = r.data.next_cursor ? `${action}${qs({ ...selection, q: q || undefined, cursor: r.data.next_cursor })}` : null;
  }
  const issue = canIssue(session.user.role);
  return <DeviceOtpView locale={locale} action={action} options={options} selection={selection} items={items} q={q} nextHref={nextHref} canIssue={issue} titleKey={issue ? "otp.admin.title" : "otp.panel.title"} nowMs={serverNowMs()} />;
}
