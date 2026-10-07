import Link from "next/link";
import { n, pctText, Tile } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getConfigAck, getDailyTracking, getPendingPhotos, getSummary, getSyncHealth } from "@/lib/dash/server";
import { latency, rollupByZone, totalsOf } from "@/lib/dash/ops";
import { isCalendarDate } from "@/lib/dates";
import { businessDate, formatDateTime, problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";

const ID = /^\d{1,12}$/;

const NA = (locale: Locale) => (
  <span data-testid="not-available" className="text-base font-normal text-slate-500">
    {t(locale, "sync.not_available")}
  </span>
);

// Sync-health dashboard for operations (F-WEB-045): login %, submit %, final submit by zone, trickle latency, quarantine backlog,
// config ack % and pending photos, with a drill from zone to route to device. Figures the contract cannot give a role are marked
// "not available" rather than guessed (docs/requests/web-dashboard-contract-gaps.md, item 2).
export default async function SyncHealthPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const date = isCalendarDate(sp.date) ? sp.date : today;
  const zone = sp.zone && ID.test(sp.zone) ? Number(sp.zone) : null;
  const route = sp.route && ID.test(sp.route) ? Number(sp.route) : null;
  const token = session.at;
  const [tracking, health, summary, ack, photos] = await Promise.all([getDailyTracking(token, date), getSyncHealth(token, date), getSummary(token, { from: date, to: date }), getConfigAck(token), getPendingPhotos(token)]);
  if (!tracking.ok || !health.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900" data-testid="sync-error">
        {problemMessage(locale, (!tracking.ok ? tracking : (health as { problem: { code: string } })).problem.code)}
      </p>
    );
  const zones = rollupByZone(tracking.data.items);
  const tot = totalsOf(zones);
  const lat = latency(health.data.items);
  const names = new Map((summary.ok ? summary.data.children : []).map((c) => [c.node.id, c.node.name ?? String(c.node.id)]));
  const d = { businessDate: date, asOf: health.data.as_of, today };
  const q = (extra: Record<string, string | number>) => `?${new URLSearchParams({ date, ...Object.fromEntries(Object.entries(extra).map(([k, v]) => [k, String(v)])) }).toString()}`;
  const cell = (key: MessageKey, id: string, body: React.ReactNode) => (
    <Tile id={id} locale={locale} titleKey={key} date={d}>
      <p className="text-2xl font-semibold tabular-nums" data-testid={`sync-${id}`}>
        {body}
      </p>
    </Tile>
  );
  const routeRows = zone === null ? [] : tracking.data.items.filter((r) => r.zone_id === zone);
  const deviceRows = route === null ? [] : health.data.items.filter((x) => x.route_ids?.includes(route));
  return (
    <div className="space-y-4" data-testid="sync-health">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "menu.sync_health")}</h1>
        <form method="get" className="flex items-end gap-2 text-sm">
          <input type="date" name="date" defaultValue={date} max={today} aria-label={t(locale, "filter.date")} className="rounded border border-slate-300 bg-white px-2 py-1" />
          <button type="submit" className="rounded border border-slate-300 bg-white px-3 py-1">
            {t(locale, "common.filter")}
          </button>
        </form>
      </div>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4" data-testid="sync-tiles">
        {cell("sync.login_pct", "login", pctText(locale, tot.login_pct))}
        {cell("sync.submit_pct", "submit", pctText(locale, tot.submit_pct))}
        {cell("sync.final_submit", "final", t(locale, "sync.zones_done", { done: n(locale, tot.zones_done), total: n(locale, tot.zones) }))}
        {cell("sync.trickle", "trickle", lat.worst === null ? NA(locale) : t(locale, "sync.seconds_worst_median", { worst: n(locale, lat.worst), median: n(locale, lat.median ?? 0) }))}
        {cell("sync.quarantine", "quarantine", n(locale, health.data.summary.quarantined))}
        {cell("sync.config_ack", "ack", ack ? <span title={t(locale, "sync.config_version", { v: n(locale, ack.version) })}>{pctText(locale, ack.pct)}</span> : NA(locale))}
        {cell("sync.pending_photos", "photos", photos === null ? NA(locale) : n(locale, photos))}
        {cell("sync.pending_rows", "pending", n(locale, health.data.items.reduce((a, i) => a + i.pending_rows_reported, 0)))}
      </div>

      <nav aria-label={t(locale, "sync.drill")} className="flex flex-wrap items-center gap-2 text-sm" data-testid="sync-crumbs">
        <Link href={q({})} className="underline">
          {t(locale, "sync.by_zone")}
        </Link>
        {zone !== null ? (
          <>
            <span>›</span>
            <Link href={q({ zone })} className="underline">
              {names.get(zone) ?? `${t(locale, "scope.zone")} ${n(locale, zone)}`}
            </Link>
          </>
        ) : null}
        {route !== null ? (
          <>
            <span>›</span>
            <span>{tracking.data.items.find((r) => r.route_id === route)?.route_name ?? n(locale, route)}</span>
          </>
        ) : null}
      </nav>

      {zone === null ? (
        <Table testId="zone-table" heads={["sync.col.zone", "sync.col.routes", "sync.col.login", "sync.col.submit", "sync.col.final"]} locale={locale}>
          {zones.map((z) => (
            <tr key={z.zone_id} data-zone={z.zone_id} data-state={z.done ? "done" : "pending"}>
              <td className="px-3 py-2">
                <Link className="underline" href={q({ zone: z.zone_id })}>
                  {names.get(z.zone_id) ?? `${t(locale, "scope.zone")} ${n(locale, z.zone_id)}`}
                </Link>
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, z.routes)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{pctText(locale, z.login_pct)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{pctText(locale, z.submit_pct)}</td>
              <td className="px-3 py-2">{t(locale, z.done ? "finalsubmit.done" : "finalsubmit.pending")}</td>
            </tr>
          ))}
        </Table>
      ) : route === null ? (
        <Table testId="route-table" heads={["tracking.col.route", "tracking.col.user", "sync.col.state", "sync.col.devices"]} locale={locale}>
          {routeRows.map((r) => (
            <tr key={r.route_id} data-route={r.route_id}>
              <td className="px-3 py-2">
                <Link className="underline" href={q({ zone: zone, route: r.route_id })}>
                  {r.route_name}
                </Link>
              </td>
              <td className="px-3 py-2">{r.user_name ?? "—"}</td>
              <td className="px-3 py-2">{t(locale, `sync.state.${r.state}` as MessageKey)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, health.data.items.filter((x) => x.route_ids?.includes(r.route_id)).length)}</td>
            </tr>
          ))}
        </Table>
      ) : (
        <Table testId="device-table" heads={["sync.col.user", "sync.col.device", "sync.col.last_contact", "sync.col.pending", "sync.col.rejected", "sync.col.quarantined", "sync.col.p95"]} locale={locale}>
          {deviceRows.map((x) => (
            <tr key={x.device_id} data-device={x.device_id} data-alert={x.held_rows_alert ? "held" : undefined}>
              <td className="px-3 py-2">{x.username ?? x.user_id}</td>
              <td className="px-3 py-2">{x.device_model ?? x.device_id}</td>
              <td className="px-3 py-2">{x.last_contact_at ? formatDateTime(locale, x.last_contact_at) : "—"}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, x.pending_rows_reported)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, x.rejected_count)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, x.quarantined_count)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{x.sync_p95_s === null || x.sync_p95_s === undefined ? "—" : n(locale, x.sync_p95_s)}</td>
            </tr>
          ))}
        </Table>
      )}
    </div>
  );
}

function Table({ heads, locale, testId, children }: { heads: MessageKey[]; locale: Locale; testId: string; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid={testId}>
        <thead className="bg-slate-50 text-left">
          <tr>
            {heads.map((h) => (
              <th key={h} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                {t(locale, h)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">{children}</tbody>
      </table>
    </div>
  );
}
