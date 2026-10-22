import Link from "next/link";
import { notFound } from "next/navigation";
import { Banner } from "@/components/kit/banner";
import { Icon } from "@/components/kit/icons";
import { Forbidden } from "@/components/forbidden";
import { SalePositionView } from "@/components/national/sale-position-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { isCalendarDate } from "@/lib/dates";
import { mapsKey } from "@/lib/maps/guard";
import { businessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";
import { distance } from "@/lib/national/fmt";
import { PIN_FILL, pinIcon } from "@/lib/national/marker-icons";
import { getSalePosition } from "@/lib/national/server";

// "Sale Position" (brief s5.4): where the SR stood against the outlet's base position, per sale, with the force-sale photo.
export default async function SalePositionPage({ params, searchParams }: { params: Promise<{ outletId: string }>; searchParams: Promise<Record<string, string | undefined>> }) {
  const [{ outletId }, sp, locale, session] = await Promise.all([params, searchParams, getLocale(), requireSession()]);
  const id = Number(outletId);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  const today = businessDate();
  const date = isCalendarDate(sp.date) && sp.date <= today ? sp.date : today;
  const r = await getSalePosition(session.at, id, date);
  if (!r.ok && r.status === 404) notFound();
  if (!r.ok && r.status === 403) return <Forbidden locale={locale} />;
  const back = (
    <Link href="/" aria-label={t(locale, "nat.back")} title={t(locale, "nat.back")} data-testid="nat-back" className="inline-flex size-(--ctl-h) items-center justify-center rounded-full border border-divider bg-raised text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
      <Icon name="chevron-left" size={20} />
    </Link>
  );
  if (!r.ok) {
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3">{back}<h1 className="text-title text-accent">{t(locale, "nat.sp.title")}</h1></div>
        <Banner tone="danger" role="alert" testId="sale-position-error">{problemMessage(locale, r.problem.code)}</Banner>
      </div>
    );
  }
  const d = r.data;
  // The header distance comes from the server, else it is the first sale's (the brief's "81.83 Meter"); with no sale there is none to show.
  const headerM = d.header_distance_m ?? d.sales[0]?.distance_m ?? null;
  const legend = [{ src: pinIcon(PIN_FILL.blue, "B"), label: "nat.sp.base" }, { src: pinIcon(PIN_FILL.yellow), label: "nat.sp.repeat" }, { src: pinIcon(PIN_FILL.teal), label: "nat.sp.edited" }] as const;
  return (
    <div className="space-y-4" data-testid="sale-position-page">
      <div className="flex items-center gap-3">{back}<h1 className="text-title text-accent">{t(locale, "nat.sp.title")}</h1></div>
      <p className="text-body" data-testid="sale-position-header">
        <span className="text-heading text-secondary">{t(locale, "nat.sp.header", { distance: headerM !== null ? distance(locale, headerM) : "—" })} » </span>
        {t(locale, "nat.sp.zone")}: {d.zone}, {t(locale, "nat.sp.route")}: {d.route}, {t(locale, "nat.sp.outlet")}: {d.outlet_code}, {t(locale, "nat.sp.date")}: {d.business_date}
      </p>
      <SalePositionView slice={d} mapsEnabled={mapsKey() !== null} />
      <section className="glass-data p-(--card-pad) text-primary" aria-labelledby="sp-ind-h" data-testid="sale-position-legend">
        <h2 id="sp-ind-h" className="mb-3 text-body-strong text-accent">{t(locale, "nat.sp.indications")}</h2>
        <ul className="flex flex-wrap gap-4">
          {legend.map((l) => (
            <li key={l.label} className="flex min-w-60 items-center gap-3 rounded-card border border-divider bg-raised px-4 py-3 text-body">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={l.src} alt="" width={22} height={30} />
              {t(locale, l.label as MessageKey)}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
