import Link from "next/link";
import { HouseDedupe } from "@/components/admin/house-dedupe";
import { loadAllRows } from "@/components/admin/crud/server";
import { one, PageHeading, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { houseGroups, type HouseRow } from "@/lib/admin/house-dedupe";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// House de-duplication (docs/34 s2): the dashboard's House filter lists each ACTIVE house once.
export default async function HouseDedupePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const showAll = one(sp.all, 1) === "1";
  const [houses, territories] = await Promise.all([loadAllRows("/v1/admin/geo/house", session.at), loadAllRows("/v1/admin/geo/territory", session.at)]);
  if (houses.failed || territories.failed) return <p role="alert" data-testid="hd-load-failed" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "hd.load_failed")}</p>;
  const groups = houseGroups(houses.rows as unknown as HouseRow[], !showAll);
  const names = Object.fromEntries(territories.rows.map((r) => [String(r.id), `${String(r.code)} · ${String(r.name)}`]));
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "hd.title")} intro={t(locale, "hd.intro")} />
      <p>
        <Link data-testid="hd-toggle" href={showAll ? "/admin/house-dedupe" : "/admin/house-dedupe?all=1"} className="text-brand-700 underline">
          {showAll ? t(locale, "common.clear") : t(locale, "hd.show_all")}
        </Link>
      </p>
      <HouseDedupe groups={groups} territories={names} canWrite={["ADMIN", "SUPERADMIN"].includes(session.user.role)} />
    </div>
  );
}
