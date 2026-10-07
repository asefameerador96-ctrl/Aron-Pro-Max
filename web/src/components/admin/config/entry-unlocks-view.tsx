// F-ADM-057 entry unlock grants: zone or route, date range, reason, expiry; replaces "call support" for back-dated web entry.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import type { EntryUnlock } from "@/lib/admin/types";
import { formatBusinessDate, formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

export function EntryUnlocksView({ locale, rows, nextHref, canWrite }: { locale: Locale; rows: EntryUnlock[]; nextHref: string | null; canWrite: boolean }) {
  const columns: Column<EntryUnlock>[] = [
    { key: "scope", header: t(locale, "cfgc.col.scope"), render: (u) => `${t(locale, `cfgc.scope.${u.scope_type}` as MessageKey)} #${formatNumber(locale, u.scope_id, { useGrouping: false })}` },
    { key: "range", header: t(locale, "eu.col.range"), render: (u) => `${formatBusinessDate(locale, u.from)} – ${formatBusinessDate(locale, u.to)}` },
    { key: "reason", header: t(locale, "cfgh.col.reason"), render: (u) => u.reason },
    { key: "exp", header: t(locale, "cfgc.col.expires"), render: (u) => formatDateTime(locale, u.expires_at) },
    { key: "st", header: t(locale, "cfgc.col.status"), render: (u) => t(locale, u.status === "active" ? "eu.active" : "eu.expired") },
    { key: "a", header: t(locale, "common.actions"), render: (u) => (canWrite && u.status === "active" ? <OpInline op="entry-unlock.expire" params={{ unlock_id: String(u.unlock_id) }} noReason label={t(locale, "eu.expire")} danger successKey="eu.expired_done" testId={`expire-${u.unlock_id}`} /> : null) },
  ];
  const fields: OpFieldDef[] = [
    { name: "scope_type", label: t(locale, "cfgc.scope.type"), kind: "enum", required: true, options: [{ value: "zone", label: t(locale, "cfgc.scope.zone") }, { value: "route", label: t(locale, "cfgc.scope.route") }] },
    { name: "scope_id", label: t(locale, "cfgc.scope.id"), kind: "int", required: true },
    { name: "from", label: t(locale, "cfgc.from"), kind: "date", required: true },
    { name: "to", label: t(locale, "cfgc.to"), kind: "date", required: true },
    { name: "ttl_h", label: t(locale, "eu.ttl"), kind: "int", hint: t(locale, "eu.ttl.hint"), max: 168, min: 1 },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "eu.title")} intro={t(locale, "eu.intro")} />
      <DataTable columns={columns} rows={rows} rowKey={(u) => String(u.unlock_id)} empty={t(locale, "common.empty")} caption={t(locale, "eu.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
      {canWrite ? (
        <Card title={t(locale, "eu.new")}>
          <OpForm op="entry-unlock.create" fields={fields} submitLabel={t(locale, "eu.new")} successKey="eu.created" testId="unlock-form" />
        </Card>
      ) : null}
    </div>
  );
}
