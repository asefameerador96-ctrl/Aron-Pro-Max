// F-TSO-022 SR Device OTP panel (view only, TSO) and F-ADM-022 device OTP administration (re-issue, search, log).
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { GeoCascade, type CascadeLevel } from "@/components/admin/kit/geo-cascade";
import { OpInline } from "@/components/admin/kit/op-inline";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { NextLink, PageHeading, qs } from "@/components/admin/kit/page";
import { GEO_LEVELS, type GeoLevel, type GeoNode } from "@/lib/admin/geo";
import type { DeviceOtp } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

export interface DeviceOtpViewProps {
  locale: Locale;
  action: string;
  options: Record<GeoLevel, GeoNode[]>;
  selection: Partial<Record<GeoLevel, string>>;
  /** null until a zone is chosen. */
  items: DeviceOtp[] | null;
  q: string;
  nextHref: string | null;
  canIssue: boolean;
  titleKey: "otp.panel.title" | "otp.admin.title";
  /** Server time in ms (a prop, so the view stays pure). */
  nowMs: number;
}

const label = (n: GeoNode, locale: Locale) => (locale === "bn" && n.name_bn ? n.name_bn : n.name);

export function DeviceOtpView({ locale, action, options, selection, items, q, nextHref, canIssue, titleKey, nowMs }: DeviceOtpViewProps) {
  const levels: CascadeLevel[] = GEO_LEVELS.map((l) => ({ param: l, label: t(locale, `otp.level.${l}` as MessageKey), value: selection[l] ?? "", options: options[l].map((n) => ({ value: String(n.id), label: label(n, locale) })) }));
  const columns: Column<DeviceOtp & { n: number }>[] = [
    { key: "n", header: t(locale, "otp.col.sr_no"), render: (r) => formatNumber(locale, r.n), align: "right" },
    { key: "ffid", header: t(locale, "otp.col.field_force_id"), render: (r) => formatNumber(locale, r.user_id, { useGrouping: false }) },
    { key: "name", header: t(locale, "otp.col.field_force_name"), render: (r) => r.full_name },
    { key: "user", header: t(locale, "otp.col.username"), render: (r) => r.username },
    { key: "zone_id", header: t(locale, "otp.col.zone_id"), render: (r) => (r.zone_id ? formatNumber(locale, r.zone_id, { useGrouping: false }) : "—") },
    { key: "created", header: t(locale, "otp.col.created"), render: (r) => <time dateTime={r.created_at}>{formatDateTime(locale, r.created_at)}</time> },
    {
      key: "otp",
      header: t(locale, "otp.col.otp"),
      render: (r) =>
        r.otp ? (
          <code className="rounded bg-slate-100 px-2 py-0.5 text-base font-semibold tracking-widest" data-testid="otp-value">
            {r.otp}
          </code>
        ) : (
          <span className="text-slate-400">—</span>
        ),
    },
  ];
  if (canIssue) {
    columns.push(
      { key: "expires", header: t(locale, "otp.col.expires"), render: (r) => (Date.parse(r.expires_at) < nowMs ? t(locale, "otp.expired") : formatDateTime(locale, r.expires_at)) },
      { key: "attempts", header: t(locale, "otp.col.attempts"), render: (r) => formatNumber(locale, r.attempts), align: "right" },
      { key: "device", header: t(locale, "otp.col.device"), render: (r) => r.device_model ?? "—" },
      { key: "issue", header: t(locale, "common.actions"), render: (r) => <OpInline op="device-otp.issue" body={{ user_id: r.user_id }} label={t(locale, "otp.issue")} successKey="otp.issued" resultField="otp" testId={`issue-${r.user_id}`} /> },
    );
  }
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, titleKey)} intro={t(locale, canIssue ? "otp.admin.intro" : "otp.panel.intro")} />
      <GeoCascade levels={levels} viewLabel={t(locale, "otp.view")} allLabel={t(locale, "common.all")} action={action} />
      {items === null ? (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600" data-testid="choose-zone">
          {t(locale, "otp.choose_zone")}
        </p>
      ) : (
        <>
          <FilterBar
            controls={[{ param: "q", label: t(locale, "common.search"), kind: "search", value: q }]}
            applyLabel={t(locale, "common.filter")}
            clearLabel={t(locale, "common.clear")}
            allLabel={t(locale, "common.all")}
            clearHref={`${action}${qs(selection)}`}
          >
            {GEO_LEVELS.map((l) => (selection[l] ? <input key={l} type="hidden" name={l} value={selection[l]} /> : null))}
          </FilterBar>
          {items.length === 0 ? (
            <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600" data-testid="no-data">
              {t(locale, "otp.no_data")}
            </p>
          ) : (
            <DataTable columns={columns} rows={items.map((r, i) => ({ ...r, n: i + 1 }))} rowKey={(r) => String(r.user_id)} empty={t(locale, "otp.no_data")} caption={t(locale, titleKey)} />
          )}
          <NextLink href={nextHref} label={t(locale, "common.next")} />
        </>
      )}
    </div>
  );
}
