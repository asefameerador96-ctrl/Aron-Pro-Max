// F-ADM-043 Config page P6: versions, compare two, revert or roll back (always a NEW version).
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import { configInputText } from "@/lib/admin/config";
import type { ConfigVersion, ConfigVersionDetail } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

export interface CompareRow {
  key: string;
  a: string;
  b: string;
}

/** Keys whose resolved value differs between two versions (a missing key shows as empty). */
export function compareVersions(a: ConfigVersionDetail, b: ConfigVersionDetail): CompareRow[] {
  const mapOf = (d: ConfigVersionDetail) => new Map(d.values.map((v) => [`${v.key}`, configInputText(v.value)]));
  const ma = mapOf(a);
  const mb = mapOf(b);
  return [...new Set([...ma.keys(), ...mb.keys()])].sort().filter((k) => ma.get(k) !== mb.get(k)).map((k) => ({ key: k, a: ma.get(k) ?? "", b: mb.get(k) ?? "" }));
}

export function HistoryView({ locale, versions, nextHref, canWrite, compare, a, b }: { locale: Locale; versions: ConfigVersion[]; nextHref: string | null; canWrite: boolean; compare: CompareRow[] | null; a: string; b: string }) {
  const columns: Column<ConfigVersion>[] = [
    { key: "v", header: t(locale, "cfgh.col.version"), render: (v) => formatNumber(locale, v.version, { useGrouping: false }) },
    { key: "k", header: t(locale, "cfgp6.col.kind"), render: (v) => t(locale, `cfgp6.kind.${v.kind}` as MessageKey) },
    { key: "s", header: t(locale, "cfgp6.col.summary"), render: (v) => v.summary },
    { key: "r", header: t(locale, "cfgh.col.risk"), render: (v) => formatNumber(locale, v.max_risk_class), align: "right" },
    { key: "c", header: t(locale, "cfgp6.col.committed"), render: (v) => <time dateTime={v.committed_at}>{formatDateTime(locale, v.committed_at)}</time> },
  ];
  if (canWrite) {
    columns.push({
      key: "a",
      header: t(locale, "common.actions"),
      render: (v) => (
        <div className="flex flex-wrap gap-2" data-testid={`version-${v.version}`}>
          <OpInline op="config.rollback" params={{ version: String(v.version) }} body={{ mode: "revert_this_version" }} label={t(locale, "cfgp6.revert")} successKey="cfgp6.reverted" testId={`revert-${v.version}`} />
          <OpInline op="config.rollback" params={{ version: String(v.version) }} body={{ mode: "rollback_to_this_version" }} label={t(locale, "cfgp6.rollback_to")} danger successKey="cfgp6.reverted" testId={`rollback-${v.version}`} />
        </div>
      ),
    });
  }
  const cmpCols: Column<CompareRow>[] = [
    { key: "k", header: t(locale, "cfgp6.key"), render: (r) => <code>{r.key}</code> },
    { key: "a", header: `${t(locale, "cfgp6.compare.a")} ${a}`, render: (r) => r.a || "∅" },
    { key: "b", header: `${t(locale, "cfgp6.compare.b")} ${b}`, render: (r) => r.b || "∅" },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "cfgp6.title")} />
      <Card title={t(locale, "cfgp6.compare")}>
        <FilterBar
          controls={[{ param: "a", label: t(locale, "cfgp6.compare.a"), kind: "int", value: a }, { param: "b", label: t(locale, "cfgp6.compare.b"), kind: "int", value: b }]}
          applyLabel={t(locale, "cfgp6.compare.go")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/config/history"
        />
        {compare === null ? null : compare.length === 0 ? <p className="text-sm text-slate-600">{t(locale, "cfgp6.compare.none")}</p> : <DataTable columns={cmpCols} rows={compare} rowKey={(r) => r.key} empty="" caption={t(locale, "cfgp6.compare")} />}
      </Card>
      <DataTable columns={columns} rows={versions} rowKey={(v) => String(v.version)} empty={t(locale, "common.empty")} caption={t(locale, "cfgp6.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
