// The dashboard's named tables (home extras and drill-downs), built once from the pure row builders so the screen, the CSV
// and the print view all carry the same strings. Units are sticks, pieces or dozens; money is taka from integer milli-taka.
import { DataTable, type Col, type TRow } from "@/components/dash/data-table";
import { n, pctText, takaText } from "@/components/dash/tiles";
import { csvInt, csvPct, csvTaka } from "@/lib/dash/csv";
import type { CallsRow, RankRow, RouteCatRow, SrRow } from "@/lib/dash/rows";
import { t, type Locale, type MessageKey } from "@/lib/i18n";

export interface Stamp {
  asOf: string | null;
  businessDate: string;
  today: string;
}
type Common = { locale: Locale; stamp: Stamp; error?: string; note?: string; className?: string };

const col = (locale: Locale, k: MessageKey, numeric = false): Col => ({ header: t(locale, k), numeric });

export function SrTable({ locale, stamp, rows, error, note, className }: Common & { rows: SrRow[] }) {
  const columns = [col(locale, "table.col.sr"), col(locale, "table.col.route"), col(locale, "table.col.calls", true), col(locale, "table.col.memos", true), col(locale, "table.col.strike", true), col(locale, "table.col.sales", true)];
  const trs: TRow[] = rows.map((r) => ({
    key: r.key,
    cells: [r.sr ?? t(locale, "table.unassigned"), r.routes.join(", "), n(locale, r.calls), n(locale, r.memos), pctText(locale, r.strike), takaText(locale, r.net_mtk)],
    csv: [r.sr ?? t(locale, "table.unassigned"), r.routes.join(" | "), csvInt(locale, r.calls), csvInt(locale, r.memos), csvPct(locale, r.strike), csvTaka(locale, r.net_mtk)],
  }));
  return <DataTable id="sr" locale={locale} heading={t(locale, "home.extras.sr")} columns={columns} rows={trs} csvName="sr-performance" {...stamp} error={error} note={note} className={className} />;
}

export function RankTable({ locale, stamp, rows, error, note, className }: Common & { rows: RankRow[] }) {
  const columns = [col(locale, "table.col.name"), col(locale, "table.col.calls", true), col(locale, "table.col.memos", true), col(locale, "table.col.strike", true), col(locale, "table.col.sales", true)];
  const trs: TRow[] = rows.map((r) => ({
    key: r.key,
    cells: [r.name, n(locale, r.calls), n(locale, r.memos), pctText(locale, r.strike), takaText(locale, r.net_mtk)],
    csv: [r.name, csvInt(locale, r.calls), csvInt(locale, r.memos), csvPct(locale, r.strike), csvTaka(locale, r.net_mtk)],
  }));
  return <DataTable id="rank" locale={locale} heading={t(locale, "home.extras.rank")} columns={columns} rows={trs} csvName="zone-ranking" {...stamp} error={error} note={note} className={className} />;
}

const unitWord = (locale: Locale, u: RouteCatRow["unit"]): string => (u ? t(locale, `unit.${u}` as MessageKey) : "—");

export function CategoryRouteTable({ locale, stamp, rows, heading, error, className }: Common & { rows: RouteCatRow[]; heading: string }) {
  const columns = [col(locale, "table.col.route"), col(locale, "table.col.sr"), col(locale, "table.col.qty", true), col(locale, "table.col.unit"), col(locale, "table.col.sales", true)];
  const trs: TRow[] = rows.map((r) => ({
    key: r.key,
    cells: [r.route, r.sr ?? t(locale, "table.unassigned"), n(locale, r.qty), unitWord(locale, r.unit), takaText(locale, r.net_mtk)],
    csv: [r.route, r.sr ?? t(locale, "table.unassigned"), csvInt(locale, r.qty), unitWord(locale, r.unit), csvTaka(locale, r.net_mtk)],
  }));
  return <DataTable id="cat-routes" locale={locale} heading={heading} columns={columns} rows={trs} csvName="category-routes" {...stamp} error={error} className={className} />;
}

export function CallsTable({ locale, stamp, rows, heading = t(locale, "drill.calls.title"), error, note, className }: Common & { rows: CallsRow[]; heading?: string }) {
  const columns = [col(locale, "table.col.route"), col(locale, "table.col.sr"), col(locale, "table.col.planned", true), col(locale, "table.col.visited", true), col(locale, "table.col.calls", true), col(locale, "table.col.cpr", true), col(locale, "table.col.strike", true)];
  const trs: TRow[] = rows.map((r) => ({
    key: r.key,
    cells: [r.route, r.sr ?? t(locale, "table.unassigned"), n(locale, r.planned), n(locale, r.visited), n(locale, r.calls), pctText(locale, r.cpr), pctText(locale, r.strike)],
    csv: [r.route, r.sr ?? t(locale, "table.unassigned"), csvInt(locale, r.planned), csvInt(locale, r.visited), csvInt(locale, r.calls), csvPct(locale, r.cpr), csvPct(locale, r.strike)],
  }));
  return <DataTable id="calls" locale={locale} heading={heading} columns={columns} rows={trs} csvName="calls-by-route" {...stamp} error={error} note={note} className={className} />;
}
