import Link from "next/link";
import { notFound } from "next/navigation";
import { Banner } from "@/components/kit/banner";
import { Icon } from "@/components/kit/icons";
import { Meter } from "@/components/kit/meter";
import { Forbidden } from "@/components/forbidden";
import { BigRing } from "@/components/national/parts";
import { RouteMapView } from "@/components/national/route-map-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { isCalendarDate } from "@/lib/dates";
import { mapsKey } from "@/lib/maps/guard";
import { businessDate, formatNumber, problemMessage, t, type MessageKey } from "@/lib/i18n";
import { clock, distance, hoursBetween, pctText, pctWhole, routeBarText } from "@/lib/national/fmt"; // tso-manual W1: + pctText, pctWhole; X3: routeBarText
import { flagIcon, personIcon, pinIcon, PIN_FILL } from "@/lib/national/marker-icons";
import { getRouteMap } from "@/lib/national/server";

// "Map Details" (brief s5.3): the route's live map for one day, with the legend, Target vs Achievement, CPR and the time card below it.
export default async function RouteMapPage({ params, searchParams }: { params: Promise<{ routeId: string }>; searchParams: Promise<Record<string, string | undefined>> }) {
  const [{ routeId }, sp, locale, session] = await Promise.all([params, searchParams, getLocale(), requireSession()]);
  const id = Number(routeId);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  const today = businessDate();
  const date = isCalendarDate(sp.date) && sp.date <= today ? sp.date : today;
  const r = await getRouteMap(session.at, id, date);
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
        <div className="flex items-center gap-3">{back}<h1 className="text-title text-accent">{t(locale, "nat.rm.title")}</h1></div>
        <Banner tone="danger" role="alert" testId="route-map-error">{problemMessage(locale, r.problem.code)}</Banner>
      </div>
    );
  }
  const d = r.data;
  const calls = d.cpr.successful_calls;
  const total = d.time.total_hours ?? hoursBetween(d.time.first_sale, d.time.last_sale);
  const avg = d.time.avg_hours; // the server's figure; the page does no arithmetic of its own on it
  const hours = (h: number | null) => (h === null ? "—" : `${formatNumber(locale, h, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${t(locale, "nat.rm.hour")}`);
  const legend = [
    { src: flagIcon(), label: "nat.rm.legend.start" }, { src: pinIcon(PIN_FILL.black), label: "nat.rm.legend.base" }, { src: pinIcon(PIN_FILL.green), label: "nat.rm.legend.geo" }, { src: pinIcon(PIN_FILL.red), label: "nat.rm.legend.photo" }, { src: personIcon(), label: "nat.rm.legend.sr" },
  ] as const;
  const card = "glass-data p-(--card-pad) text-primary";
  // tso-manual W1 (D-TSO-MAN-10): each whole % rounded once from the integers on the payload; the bp still draws the bar.
  // tso-manual X3 (R-W-13 (5)): a bar prints by the Sales-card rule from monthly_target_qty and working_days when the server sends
  // them; W1's sales-over-Decimal3-daily-target path, then the bp, remain the fallbacks for an older answer (routeBarText).
  const barText = (x: (typeof d.target_vs_achievement)[number]) => routeBarText(locale, x);
  const cprText = pctText(locale, pctWhole(calls, d.cpr.target_outlets), 0);
  return (
    <div className="space-y-4" data-testid="route-map-page">
      <div className="flex items-center gap-3">{back}<h1 className="text-title text-accent">{t(locale, "nat.rm.title")}</h1></div>
      <p className="text-body text-primary" data-testid="route-map-header">
        <span className="text-heading text-secondary">{t(locale, "nat.rm.header")} » </span>
        <span data-testid="route-map-sr">{d.sr_name}</span>, {t(locale, "nat.rm.territory")}: {d.territory_name} ({d.territory_code}), {t(locale, "nat.rm.zone")}: {d.zone}, {t(locale, "nat.rm.route")}: {d.route}, {t(locale, "nat.rm.date")}: {d.business_date}
      </p>
      <RouteMapView slice={d} date={date} mapsEnabled={mapsKey() !== null} />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
        <section className={card} aria-label={t(locale, "nat.rm.legend.start")} data-testid="route-legend">
          <ul className="space-y-3">
            {legend.map((l) => (
              <li key={l.label} className="flex items-center gap-3 text-body">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={l.src} alt="" width={22} height={28} className="shrink-0" />
                {t(locale, l.label)}
              </li>
            ))}
          </ul>
        </section>
        <section className={card} aria-labelledby="tva-h" data-testid="route-tva">
          <h2 id="tva-h" className="mb-3 border-b border-accent-graphic/40 pb-2 text-title text-secondary">{t(locale, "nat.rm.tva")}</h2>
          <ul className="space-y-3">
            {d.target_vs_achievement.map((x) => (
              <li key={x.category} data-testid={`tva-${x.category}`} className="grid grid-cols-[4.5rem_1fr_3rem] items-center gap-2 text-body">
                <span className="text-secondary">{t(locale, `nat.rm.cat.${x.category}` as MessageKey)}</span>
                <Meter pct={x.pct_bp === null ? null : x.pct_bp / 100} valueText={barText(x)} label={t(locale, `nat.rm.cat.${x.category}` as MessageKey)} className="!h-3" />
                <span className="text-body-strong tabular-nums">{barText(x)}</span>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-body">{t(locale, "nat.rm.last_place")} : <span className="text-success-on-container" data-testid="route-last-place">{d.last_sale_place ?? "—"}</span></p>
        </section>
        <section className={card} aria-labelledby="cpr-h" data-testid="route-cpr">
          <h2 id="cpr-h" className="mb-3 border-b border-accent-graphic/40 pb-2 text-title text-secondary">{t(locale, "nat.rm.cpr")}</h2>
          <div className="flex items-center justify-between gap-3">
            <BigRing bp={d.cpr.cpr_pct_bp} text={cprText} label={t(locale, "nat.rm.cpr")} tone="green" size={110} testId="route-cpr-ring" />
            <dl className="space-y-3 text-center">
              {([["nat.rm.cpr.target", d.cpr.target_outlets], ["nat.rm.cpr.success", calls], ["nat.rm.cpr.geo", d.cpr.geo_validation]] as const).map(([k, v]) => (
                <div key={k}>
                  <dd className="text-heading text-cyan tabular-nums">{formatNumber(locale, v)}</dd>
                  <dt className="text-caption text-secondary">{t(locale, k)}</dt>
                </div>
              ))}
            </dl>
          </div>
        </section>
        <section className={card} aria-label={t(locale, "nat.rm.first")} data-testid="route-time">
          <dl className="space-y-3 text-body">
            {([["clock", "nat.rm.first", clock(locale, d.time.first_sale, true) || "—"], ["location", "nat.rm.last", clock(locale, d.time.last_sale, true) || "—"], ["routes", "nat.rm.distance", distance(locale, d.time.distance_m)], ["clock", "nat.rm.total_time", hours(total)], ["clock", "nat.rm.avg_time", hours(avg)]] as const).map(([icon, k, v]) => (
              <div key={k} className="flex items-center gap-3">
                <Icon name={icon} size={22} className="shrink-0 text-secondary" />
                <dt className="text-secondary">{t(locale, k)} :</dt>
                <dd className="tabular-nums" data-testid={`time-${k}`}>{v}</dd>
              </div>
            ))}
          </dl>
        </section>
      </div>
    </div>
  );
}
