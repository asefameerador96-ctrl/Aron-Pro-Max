// The twelve cards of the national dashboard (brief s2 to s5), server-rendered from one slice each. A slice the token may not read (403) leaves
// its card out; any other failure shows a retry in place of the card body. Figures are formatted here, once, for the card and its pop-up.
import type { ReactNode } from "react";
import { Icon } from "@/components/kit/icons";
import { Meter } from "@/components/kit/meter";
import { ColumnChart } from "@/components/dash/charts";
import { RetryButton } from "@/components/dash/table-actions";
import type { ApiOutcome } from "@/lib/api/client";
import { formatNumber, problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";
import { clock, pct as pctText, qty } from "@/lib/national/fmt";
import { achievementPct, share } from "@/lib/national/query";
import type { BreakdownBy, BreakdownMeasure, BreakdownSlice, CategoryCode, ChannelCode, ChannelSlice, CprSlice, FinalSubmitSlice, GeoFencingSlice, LoginSubmitSlice, SalesSlice } from "@/lib/national/types";
import { InfoPopup } from "./info-popup";
import { BigRing, CardError, CHANNEL_COLOR, channelOrder, Figure, NatCard } from "./parts";
import { ChannelPie, ChannelRadial } from "./channel-charts";

type Slot<T> = ApiOutcome<T>;

/** What a card shows for a slice: nothing (403: not this account's card), an error with retry, or its body. */
function Body<T>({ slot, locale, children }: { slot: Slot<T>; locale: Locale; children: (data: T) => ReactNode }) {
  if (slot.ok) return <>{children(slot.data)}</>;
  return <CardError text={`${t(locale, "nat.card_error")} ${problemMessage(locale, slot.problem.code)}`} retry={<RetryButton msg={t(locale, "nat.card_retry")} />} />;
}
const visible = <T,>(slot: Slot<T>): boolean => slot.ok || slot.status !== 403;

const channelName = (locale: Locale, code: ChannelCode): string => (code === "HoReCa" ? t(locale, "nat.channel.HoReCa") : t(locale, "nat.channel", { code }));

/** Row 1: four sales cards, each in its own unit, ring = total / daily target, round "i" with Daily Target / Sales so far / Remaining. */
export function SalesCards({ slot, locale }: { slot: Slot<SalesSlice>; locale: Locale }) {
  const cats: CategoryCode[] = ["cigarette", "bidi", "lighter", "match"];
  if (!visible(slot)) return null;
  return (
    <>
      {cats.map((cat) => {
        const title = t(locale, `nat.sales.${cat}` as MessageKey);
        return (
          <NatCard key={cat} id={`sales-${cat}`} title={title} className="min-h-60 xl:col-span-3">
            <Body slot={slot} locale={locale}>
              {(d) => {
                const c = d.cards.find((x) => x.category === cat);
                if (!c) return <p className="text-center text-body text-secondary">{t(locale, "nat.empty")}</p>;
                const p = c.pct ?? achievementPct(c.total, c.daily_target);
                const rows = c.daily_target === null ? null : [
                  { label: t(locale, "nat.info.daily_target"), value: qty(locale, c.daily_target) },
                  { label: t(locale, "nat.info.sales_so_far"), value: qty(locale, c.total) },
                  { label: t(locale, "nat.info.remaining"), value: qty(locale, c.remaining ?? Math.max(0, c.daily_target - c.total)) },
                ];
                return (
                  <>
                    <div className="flex items-center justify-between gap-3 pb-8">
                      <Figure value={qty(locale, c.total)} caption={t(locale, "nat.total_sales")} testId={`sales-total-${cat}`} />
                      <BigRing pct={p} text={p === null ? "—" : pctText(locale, p)} label={title} testId={`sales-ring-${cat}`} />
                    </div>
                    <InfoPopup testId={`sales-info-${cat}`} openLabel={t(locale, "nat.info.open", { card: title })} title={t(locale, "nat.info.title")} rows={rows} emptyText={t(locale, "nat.info.no_target")} closeLabel={t(locale, "nat.info.close")} />
                  </>
                );
              }}
            </Body>
          </NatCard>
        );
      })}
    </>
  );
}

/** Live CPR: Target Outlet and Successful Calls, ring = calls / outlets (green). */
export function CprCard({ slot, locale }: { slot: Slot<CprSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="cpr" title={t(locale, "nat.cpr")} className="xl:col-span-3">
      <Body slot={slot} locale={locale}>
        {(d) => {
          const p = d.target_outlets > 0 ? (d.successful_calls / d.target_outlets) * 100 : null;
          return (
            <div className="flex items-center justify-between gap-3">
              <div className="flex flex-col gap-5">
                <Figure value={qty(locale, d.target_outlets)} caption={t(locale, "nat.cpr.target")} testId="cpr-target" />
                <Figure value={qty(locale, d.successful_calls)} caption={t(locale, "nat.cpr.calls")} testId="cpr-calls" />
              </div>
              <BigRing pct={p} text={pctText(locale, p)} label={t(locale, "nat.cpr")} tone="green" testId="cpr-ring" />
            </div>
          );
        }}
      </Body>
    </NatCard>
  );
}

function ChannelLegend({ rows, locale, valueOf, total, testPrefix }: { rows: { code: ChannelCode; value: number }[]; locale: Locale; valueOf: (n: number) => string; total: number; testPrefix: string }) {
  return (
    <ul className="space-y-1.5 text-center" aria-label={testPrefix}>
      {rows.map((r) => (
        <li key={r.code} data-testid={`${testPrefix}-${r.code}`} data-value={r.value}>
          <p className="flex items-center justify-center gap-1.5 text-body-strong tabular-nums text-primary">
            <span className="size-2.5 shrink-0 rounded-full" style={{ background: CHANNEL_COLOR[r.code] }} aria-hidden="true" />
            {valueOf(r.value)} ({pctText(locale, share(r.value, total))})
          </p>
          <p className="text-caption text-secondary">{channelName(locale, r.code)}</p>
        </li>
      ))}
    </ul>
  );
}
const sorted = (s: ChannelSlice) => [...s.channels].sort((a, b) => channelOrder(a.code) - channelOrder(b.code));

/** By Channel Successful Call: list at the left, concentric radial chart at the right. */
export function ChannelCallsCard({ slot, locale }: { slot: Slot<ChannelSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="ch-calls" title={t(locale, "nat.ch_calls")} className="xl:col-span-3">
      <Body slot={slot} locale={locale}>
        {(d) => {
          const rows = sorted(d);
          const total = rows.reduce((s, r) => s + r.value, 0);
          return (
            <div className="flex items-center justify-between gap-2">
              <ChannelLegend rows={rows} locale={locale} valueOf={(v) => qty(locale, v)} total={total} testPrefix="ch-calls" />
              <ChannelRadial rows={rows} label={t(locale, "nat.ch_calls")} />
            </div>
          );
        }}
      </Body>
    </NatCard>
  );
}

/** Final Submit Status: zones, service zones, remaining, and the ring of final submits over service zones. */
export function FinalSubmitCard({ slot, locale }: { slot: Slot<FinalSubmitSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="final" title={t(locale, "nat.final")} className="xl:col-span-3">
      <Body slot={slot} locale={locale}>
        {(d) => {
          const remaining = Math.max(0, d.service_zone - d.submitted);
          const p = d.service_zone > 0 ? (d.submitted / d.service_zone) * 100 : null;
          return (
            <div className="flex items-center justify-between gap-3">
              <div className="flex flex-col gap-3">
                <Figure value={formatNumber(locale, d.total_zone)} caption={t(locale, "nat.final.total_zone")} captionTone="primary" testId="final-total" />
                <Figure value={formatNumber(locale, d.service_zone)} caption={t(locale, "nat.final.service_zone")} captionTone="primary" testId="final-service" />
                <Figure value={formatNumber(locale, remaining)} caption={t(locale, "nat.final.remaining")} captionTone="primary" testId="final-remaining" />
              </div>
              <div className="flex flex-col items-center gap-1">
                <BigRing pct={p} text={pctText(locale, p)} label={t(locale, "nat.final")} tone="plain" size={120} testId="final-ring" />
                <p className="text-body-strong text-cyan">{t(locale, "nat.final.submitted")}</p>
                <p className="text-body-strong tabular-nums" data-testid="final-submitted">{formatNumber(locale, d.submitted)}</p>
              </div>
            </div>
          );
        }}
      </Body>
    </NatCard>
  );
}

function StatusBlock({ icon, title, pct, pairs, locale, testId }: { icon: "download" | "upload"; title: string; pct: number | null; pairs: [string, string][]; locale: Locale; testId: string }) {
  const text = pctText(locale, pct);
  return (
    <div data-testid={testId}>
      <div className="flex items-center gap-2">
        <Icon name={icon} size={24} className="text-accent" />
        <p className="flex-1 text-body-strong text-accent">{title}</p>
        <p className="text-body-strong tabular-nums" data-testid={`${testId}-pct`}>{text}</p>
      </div>
      <Meter pct={pct} valueText={text} label={title} className="mt-1 !h-3" />
      <dl className="mt-2 grid grid-cols-2 gap-2 text-caption">
        {pairs.map(([label, value]) => (
          <div key={label}>
            <dd className="text-body-strong tabular-nums text-primary">{value}</dd>
            <dt className="text-accent">{label}</dt>
          </div>
        ))}
      </dl>
    </div>
  );
}

/** Login/Submit Status: Login = routes checked in and synced / target routes; Submit = Bikroy Joma done / routes logged in. */
export function LoginSubmitCard({ slot, locale }: { slot: Slot<LoginSubmitSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="login" title={t(locale, "nat.login")} className="xl:col-span-3">
      <Body slot={slot} locale={locale}>
        {(d) => (
          <div className="space-y-4">
            <StatusBlock locale={locale} testId="login-status" icon="download" title={t(locale, "nat.login.status")} pct={d.target_routes > 0 ? (d.logged_in / d.target_routes) * 100 : null} pairs={[[t(locale, "nat.login.target_route"), formatNumber(locale, d.target_routes)], [t(locale, "nat.login.successful"), formatNumber(locale, d.logged_in)]]} />
            <StatusBlock locale={locale} testId="submit-status" icon="upload" title={t(locale, "nat.submit.status")} pct={d.logged_in > 0 ? (d.submitted / d.logged_in) * 100 : null} pairs={[[t(locale, "nat.submit.logged_in"), formatNumber(locale, d.logged_in)], [t(locale, "nat.submit.done"), formatNumber(locale, d.submitted)]]} />
          </div>
        )}
      </Body>
    </NatCard>
  );
}

/** By Channel STD: cigarette only; list at the left, flat pie at the right (the original's 3D pie is flat here, UI-WEB-08). */
export function ChannelStdCard({ slot, locale }: { slot: Slot<ChannelSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="ch-std" title={t(locale, "nat.ch_std")} className="min-h-80 md:col-span-1 xl:col-span-4">
      <Body slot={slot} locale={locale}>
        {(d) => {
          const rows = sorted(d);
          const total = rows.reduce((s, r) => s + r.value, 0);
          return (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <ChannelLegend rows={rows} locale={locale} valueOf={(v) => qty(locale, v)} total={total} testPrefix="ch-std" />
              <ChannelPie rows={rows} label={t(locale, "nat.ch_std")} locale={locale} />
            </div>
          );
        }}
      </Body>
    </NatCard>
  );
}

const MODES: { measure: BreakdownMeasure; by: BreakdownBy; label: MessageKey }[] = [
  { measure: "std", by: "segment", label: "nat.by_segment" }, { measure: "std", by: "brand", label: "nat.by_brand" }, { measure: "std", by: "variant", label: "nat.by_variant" },
  { measure: "memo", by: "segment", label: "nat.by_segment" }, { measure: "memo", by: "brand", label: "nat.by_brand" }, { measure: "memo", by: "variant", label: "nat.by_variant" },
];

/** Breakdown: bars per segment, brand or variant as STD (quantity) or Memo (memo count); the gear picks one of six settings (links, no script). */
export function BreakdownCard({ slot, locale, hrefFor, measure, by }: { slot: Slot<BreakdownSlice>; locale: Locale; hrefFor: (measure: BreakdownMeasure, by: BreakdownBy) => string; measure: BreakdownMeasure; by: BreakdownBy }) {
  if (!visible(slot)) return null;
  const gear = (
    <details className="no-print relative" data-testid="breakdown-gear">
      <summary aria-label={t(locale, "nat.gear")} title={t(locale, "nat.gear")} className="flex size-(--ctl-h) cursor-pointer list-none items-center justify-center rounded-full text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus [&::-webkit-details-marker]:hidden">
        <Icon name="config" size={26} />
      </summary>
      <div className="glass-pop absolute end-0 top-11 z-30 w-52 p-2 text-body" role="menu" aria-label={t(locale, "nat.gear")}>
        {(["std", "memo"] as const).map((m) => (
          <div key={m} className="mb-2 last:mb-0">
            <p className="px-2 py-1 text-secondary">{t(locale, m === "std" ? "nat.by_std" : "nat.by_memo")}</p>
            {MODES.filter((x) => x.measure === m).map((x) => {
              const on = x.measure === measure && x.by === by;
              return (
                <a key={x.by} role="menuitemradio" aria-checked={on} aria-current={on ? "true" : undefined} href={hrefFor(x.measure, x.by)} data-testid={`breakdown-${x.measure}-${x.by}`} className={`block min-h-(--ctl-h) rounded-chip px-3 py-2 ${on ? "bg-accent text-on-accent" : "bg-pressed text-primary hover:brightness-95"} mb-1 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus`}>
                  {t(locale, x.label)}
                </a>
              );
            })}
          </div>
        ))}
      </div>
    </details>
  );
  const title = (
    <span>
      {t(locale, "nat.breakdown")} - <span className={measure === "std" ? "" : "text-cyan"}>{t(locale, "nat.std")}</span> /{" "}
      <span className={measure === "memo" ? "" : "text-cyan"}>{t(locale, "nat.memo")}</span>
    </span>
  );
  return (
    <section data-testid="nat-breakdown" aria-label={`${t(locale, "nat.breakdown")} ${t(locale, measure === "std" ? "nat.std" : "nat.memo")}`} className="glass-data relative flex min-h-80 flex-col gap-2 p-(--card-pad) text-primary md:col-span-2 xl:col-span-8">
      <div className="flex items-center justify-between gap-2 border-b border-accent-graphic/40 pb-1">
        <h2 className="min-w-0 flex-1 truncate text-body-strong text-accent" data-testid="breakdown-title">{title}</h2>
        {gear}
      </div>
      <Body slot={slot} locale={locale}>
        {(d) => {
          const mixed = measure === "std" && new Set(d.rows.map((r) => r.unit)).size > 1;
          return d.rows.length === 0 ? (
            <p className="py-10 text-center text-body text-secondary">{t(locale, "nat.empty")}</p>
          ) : (
            <>
              <ColumnChart testId="breakdown-chart" label={t(locale, "nat.breakdown")} height={220} axisText={(v) => qty(locale, v)} rows={d.rows.map((r) => ({ key: r.key, label: r.label, value: r.value, valueText: qty(locale, r.value), sub: mixed && r.unit ? t(locale, `nat.unit.${r.unit}` as MessageKey) : undefined }))} />
              {mixed ? <p className="text-caption text-secondary" data-testid="breakdown-unit-note">{t(locale, "nat.breakdown.unit_note")}</p> : null}
            </>
          );
        }}
      </Body>
    </section>
  );
}

/** Geo Fencing: Geo Validation and Total Calls, ring = validated / total with two decimals (97.96%). */
export function GeoCard({ slot, locale }: { slot: Slot<GeoFencingSlice>; locale: Locale }) {
  if (!visible(slot)) return null;
  return (
    <NatCard id="geo" title={t(locale, "nat.geo")} className="min-h-80 md:col-span-1 xl:col-span-4">
      <Body slot={slot} locale={locale}>
        {(d) => {
          const p = d.total_calls > 0 ? (d.geo_validation / d.total_calls) * 100 : null;
          return (
            <div className="flex items-center justify-between gap-3">
              <div className="flex flex-col gap-8">
                <Figure value={qty(locale, d.geo_validation)} caption={t(locale, "nat.geo.valid")} testId="geo-valid" />
                <Figure value={qty(locale, d.total_calls)} caption={t(locale, "nat.geo.total")} testId="geo-total" />
              </div>
              <BigRing pct={p} text={pctText(locale, p, 2)} label={t(locale, "nat.geo")} tone="green" size={190} testId="geo-ring" />
            </div>
          );
        }}
      </Body>
    </NatCard>
  );
}

export { clock };
