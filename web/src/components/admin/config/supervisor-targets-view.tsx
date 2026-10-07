import { FilterBar } from "@/components/admin/kit/filter-bar";
import { PageHeading } from "@/components/admin/kit/page";
import type { SupervisorTarget } from "@/lib/admin/types";
import { t, type Locale } from "@/lib/i18n";
import { SupervisorTargetsEditor } from "./supervisor-targets-editor";

export function SupervisorTargetsView({ locale, month, zone, rows, canWrite }: { locale: Locale; month: string; zone: string; rows: SupervisorTarget[] | null; canWrite: boolean }) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "st.title")} intro={t(locale, "st.intro")} />
      <FilterBar
        controls={[{ param: "month", label: t(locale, "st.month"), kind: "search", value: month }, { param: "zone_id", label: t(locale, "cfgr.zone_filter"), kind: "int", value: zone }]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/supervisor-targets"
      />
      {rows === null ? <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "st.choose")}</p> : <SupervisorTargetsEditor key={`${month}-${zone}`} month={month} initial={rows} names={{}} canWrite={canWrite} />}
    </div>
  );
}
