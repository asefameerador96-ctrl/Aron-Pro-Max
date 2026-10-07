import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canTeamOp } from "@/lib/admin/access";
import { GEO_LEVELS, loadGeoOptions, type GeoLevel } from "@/lib/admin/geo";
import { isRealDate } from "@/lib/admin/time";
import type { FinalSubmitPreview } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";
import { FinalSubmitView } from "./final-submit-view";

export async function FinalSubmitPageContent({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  const role = session.user.role;
  if (!canTeamOp("day.final-submit", role)) return <Forbidden locale={locale} />;
  const selection: Partial<Record<GeoLevel, string>> = {};
  for (const l of GEO_LEVELS) {
    const v = one(sp[l], 16);
    if (v && /^[1-9]\d{0,14}$/.test(v)) selection[l] = v;
  }
  const today = businessDate();
  const d = one(sp.date, 10);
  const date = isRealDate(d) && d <= today ? d : today;
  const options = await loadGeoOptions(session.at, selection);
  let preview: FinalSubmitPreview | null = null;
  if (selection.zone) {
    const r = await apiGet<FinalSubmitPreview>("/v1/day/final-submit/preview", session.at, { zone_id: selection.zone, business_date: date });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    preview = r.data;
  }
  return <FinalSubmitView locale={locale} options={options} selection={selection} date={date} today={today} preview={preview} canSubmit={canTeamOp("day.final-submit", role)} canVoid={canTeamOp("day.data-void", role)} />;
}
