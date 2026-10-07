import { TakeAction } from "@/components/dash/take-action";
import { n, pctText, takaText } from "@/components/dash/tiles";
import type { Schemas } from "@/contract/types";
import { BUCKETS, countBuckets, type Bucket } from "@/lib/dash/buckets";
import { t, type Locale, type MessageKey } from "@/lib/i18n";

export const BUCKET_KEY: Record<Bucket, MessageKey> = {
  ge_100: "tracking.bucket.ge_100",
  from_90: "tracking.bucket.from_90",
  from_80: "tracking.bucket.from_80",
  below_80: "tracking.bucket.below_80",
  exception: "tracking.bucket.exception",
  not_logged_in: "tracking.bucket.not_logged_in",
};
const TONE: Record<Bucket, string> = { ge_100: "bg-emerald-50 text-emerald-900", from_90: "bg-lime-50 text-lime-900", from_80: "bg-yellow-50 text-yellow-900", below_80: "bg-red-50 text-red-900", exception: "bg-sky-50 text-sky-900", not_logged_in: "bg-slate-100 text-slate-800" };

type Row = Schemas["DailyTrackingRow"];

/** Bucket cards with an optional comparator count per bucket. */
export function BucketCards({ locale, items, compare, compareLabel }: { locale: Locale; items: Row[]; compare?: Record<Bucket, number> | null; compareLabel?: string }) {
  const counts = countBuckets(items);
  return (
    <ul className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-6" data-testid="bucket-cards">
      {BUCKETS.map((b) => (
        <li key={b} data-bucket={b} className={`rounded-lg p-3 ${TONE[b]}`}>
          <p className="text-xs font-semibold">{t(locale, BUCKET_KEY[b])}</p>
          <p className="text-2xl font-semibold tabular-nums" data-testid={`bucket-count-${b}`}>
            {n(locale, counts[b])}
          </p>
          {compare ? (
            <p className="text-xs" data-testid={`bucket-compare-${b}`}>
              {compareLabel}: {n(locale, compare[b])}
            </p>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

export function TrackingTable({ locale, items, businessDate, canAct }: { locale: Locale; items: Row[]; businessDate: string; canAct: boolean }) {
  if (items.length === 0) return <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "report.empty")}</p>;
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="tracking-table">
        <thead className="bg-slate-50 text-left">
          <tr>
            {(["tracking.col.route", "tracking.col.user", "tracking.col.bucket", "tracking.col.target", "tracking.col.visited", "tracking.col.successful", "tracking.col.memos", "tracking.col.net", "tracking.col.achievement"] as const).map((k) => (
              <th key={k} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                {t(locale, k)}
              </th>
            ))}
            {canAct ? <th scope="col" className="px-3 py-2">{t(locale, "common.actions")}</th> : null}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {items.map((r) => (
            <tr key={r.route_id} data-route={r.route_id} data-bucket={r.bucket}>
              <td className="whitespace-nowrap px-3 py-2">{r.route_name}</td>
              <td className="whitespace-nowrap px-3 py-2">{r.user_name ?? "—"}</td>
              <td className="whitespace-nowrap px-3 py-2">{t(locale, BUCKET_KEY[r.bucket])}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, r.target_outlets)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, r.visited_outlets)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, r.successful_calls)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{n(locale, r.active_memo_count)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{takaText(locale, r.net_mtk)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{pctText(locale, r.tilldate_target_achievement_pct)}</td>
              {canAct ? (
                <td className="px-3 py-2">
                  <TakeAction routeId={r.route_id} businessDate={businessDate} labels={{ open: t(locale, "tracking.take_action"), note: t(locale, "tracking.note"), send: t(locale, "tracking.send"), sent: t(locale, "tracking.sent"), failed: t(locale, "tracking.failed"), notified: t(locale, "tracking.notified") }} />
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
