// F-ADM-009 device management and F-ADM-048 Config page P11: bound users, last contact, versions, trust, printer, revoke.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpInline } from "@/components/admin/kit/op-inline";
import { NextLink, PageHeading } from "@/components/admin/kit/page";
import type { Device } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

const STATES = ["enrolled", "active", "suspended", "revoked", "replaced"] as const;
const TRUSTS = ["high", "normal", "low", "blocked"] as const;
const FLAVOURS = ["sr", "amo", "tso"] as const;
const opts = (locale: Locale, prefix: string, values: readonly string[]) => values.map((v) => ({ value: v, label: t(locale, `${prefix}.${v}` as MessageKey) }));

export function deviceActions(locale: Locale, d: Device, canWrite: boolean) {
  const params = { device_id: String(d.device_id) };
  return (
    <div className="flex flex-wrap items-start gap-2" data-testid={`device-${d.device_id}`}>
      <Link href={`/admin/devices/${d.device_id}`} className="rounded border border-slate-300 px-2 py-1 text-xs hover:bg-slate-100">
        {t(locale, "dev.action.details")}
      </Link>
      {canWrite && d.status === "active" ? <OpInline op="device.state" params={params} body={{ action: "suspend" }} label={t(locale, "dev.action.suspend")} successKey="dev.action.done" testId={`suspend-${d.device_id}`} /> : null}
      {canWrite && (d.status === "active" || d.status === "suspended" || d.status === "enrolled") ? <OpInline op="device.state" params={params} body={{ action: "revoke" }} label={t(locale, "dev.action.revoke")} danger successKey="dev.action.done" testId={`revoke-${d.device_id}`} /> : null}
      {canWrite && d.status === "suspended" ? <OpInline op="device.state" params={params} body={{ action: "reactivate" }} label={t(locale, "dev.action.reactivate")} successKey="dev.action.done" testId={`reactivate-${d.device_id}`} /> : null}
      {canWrite && (d.status === "active" || d.status === "suspended") ? (
        <Link href={`/admin/devices/${d.device_id}/replace`} className="rounded border border-slate-300 px-2 py-1 text-xs hover:bg-slate-100">
          {t(locale, "dev.action.replace")}
        </Link>
      ) : null}
    </div>
  );
}

export function DevicesView({ locale, rows, filters, nextHref, canWrite }: { locale: Locale; rows: Device[]; filters: { status: string; trust_level: string; flavour: string; q: string; zone_id: string }; nextHref: string | null; canWrite: boolean }) {
  const columns: Column<Device>[] = [
    { key: "users", header: t(locale, "dev.col.users"), render: (d) => (d.bound_users.length ? d.bound_users.map((u) => u.username).join(", ") : "—") },
    { key: "flavour", header: t(locale, "dev.col.flavour"), render: (d) => t(locale, `dev.flavour.${d.flavour}` as MessageKey) },
    { key: "model", header: t(locale, "dev.col.model"), render: (d) => (d.device_info ? `${d.device_info.manufacturer} ${d.device_info.model}` : "—") },
    { key: "seen", header: t(locale, "dev.col.last_seen"), render: (d) => (d.last_contact_at ? <time dateTime={d.last_contact_at}>{formatDateTime(locale, d.last_contact_at)}</time> : t(locale, "dev.never")) },
    { key: "app", header: t(locale, "dev.col.app"), render: (d) => d.app_version ?? "—" },
    { key: "cfg", header: t(locale, "dev.col.config"), render: (d) => (d.config_version_applied == null ? "—" : formatNumber(locale, d.config_version_applied, { useGrouping: false })) },
    { key: "trust", header: t(locale, "dev.col.trust"), render: (d) => `${t(locale, `dev.trust.${d.trust_level}` as MessageKey)} (${t(locale, `dev.integrity.${d.integrity_verdict ?? "unevaluated"}` as MessageKey)})` },
    { key: "printer", header: t(locale, "dev.col.printer"), render: (d) => d.last_status?.printer?.bonded_name ?? "—" },
    { key: "state", header: t(locale, "dev.col.state"), render: (d) => t(locale, `dev.state.${d.status}` as MessageKey) },
    { key: "actions", header: t(locale, "common.actions"), render: (d) => deviceActions(locale, d, canWrite) },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "dev.title")} intro={t(locale, "dev.intro")} />
      <FilterBar
        controls={[
          { param: "q", label: t(locale, "common.search"), kind: "search", value: filters.q },
          { param: "zone_id", label: t(locale, "cfgr.zone_filter"), kind: "int", value: filters.zone_id },
          { param: "status", label: t(locale, "dev.filter.status"), kind: "enum", value: filters.status, options: opts(locale, "dev.state", STATES) },
          { param: "trust_level", label: t(locale, "dev.filter.trust"), kind: "enum", value: filters.trust_level, options: opts(locale, "dev.trust", TRUSTS) },
          { param: "flavour", label: t(locale, "dev.filter.flavour"), kind: "enum", value: filters.flavour, options: opts(locale, "dev.flavour", FLAVOURS) },
        ]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/devices"
      />
      <p className="text-xs text-slate-600">{t(locale, "dev.revoke_note")}</p>
      <DataTable columns={columns} rows={rows} rowKey={(d) => String(d.device_id)} empty={t(locale, "common.empty")} caption={t(locale, "dev.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
