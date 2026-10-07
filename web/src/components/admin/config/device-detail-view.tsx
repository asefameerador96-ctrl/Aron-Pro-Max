// P11 detail: bound users, latest status, status history and remote requests of one phone.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { Device, DeviceStatusReport, Directive } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { deviceActions } from "./devices-view";

const DIRECTIVES = ["send_status", "redownload_bundle", "upload_support_bundle"] as const;

export function DeviceDetailView({ locale, device, history, directives, canWrite }: { locale: Locale; device: Device; history: DeviceStatusReport[]; directives: Directive[]; canWrite: boolean }) {
  const hist: Column<DeviceStatusReport>[] = [
    { key: "at", header: t(locale, "dev.col.reported"), render: (r) => formatDateTime(locale, r.reported_at) },
    { key: "app", header: t(locale, "dev.col.app"), render: (r) => r.app_version },
    { key: "bat", header: t(locale, "dev.col.battery"), render: (r) => `${formatNumber(locale, r.battery_pct)}%`, align: "right" },
    { key: "pend", header: t(locale, "dev.col.pending"), render: (r) => formatNumber(locale, r.pending_rows ?? 0), align: "right" },
    { key: "blk", header: t(locale, "dev.col.blocking"), render: (r) => t(locale, r.blocking_active ? "common.yes" : "common.no") },
  ];
  const dir: Column<Directive>[] = [
    { key: "t", header: t(locale, "dev.col.directive"), render: (d) => t(locale, `dev.directive.${d.type}` as MessageKey) },
    { key: "c", header: t(locale, "cfgc.col.created"), render: (d) => formatDateTime(locale, d.created_at) },
    { key: "a", header: t(locale, "dev.col.acked"), render: (d) => (d.acked_at ? formatDateTime(locale, d.acked_at) : "—") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "dev.detail.title", { id: formatNumber(locale, device.device_id, { useGrouping: false }) })} actions={<Link href="/admin/devices" className="text-sm text-brand-700 underline">{t(locale, "dev.back")}</Link>} />
      <Card>
        <p className="text-sm">
          {t(locale, "dev.col.state")}: <strong>{t(locale, `dev.state.${device.status}` as MessageKey)}</strong> · {t(locale, "dev.col.trust")}: <strong>{t(locale, `dev.trust.${device.trust_level}` as MessageKey)}</strong> · {t(locale, "dev.col.app")}: {device.app_version ?? "—"} · {t(locale, "dev.col.config")}: {device.config_version_applied ?? "—"}
        </p>
        {deviceActions(locale, device, canWrite)}
      </Card>
      <Card title={t(locale, "dev.detail.users")}>
        <ul className="text-sm">
          {device.bound_users.map((u) => (
            <li key={u.user_id}>
              {u.username} · {t(locale, "dev.bound.ordinal", { n: formatNumber(locale, u.bind_ordinal) })} · {formatDateTime(locale, u.bound_at)}
            </li>
          ))}
        </ul>
      </Card>
      <Card title={t(locale, "dev.detail.history")}>
        <DataTable columns={hist} rows={history} rowKey={(r) => r.reported_at} empty={t(locale, "common.empty")} caption={t(locale, "dev.detail.history")} />
      </Card>
      <Card title={t(locale, "dev.detail.directives")}>
        {canWrite ? (
          <div className="flex flex-wrap gap-2">
            {DIRECTIVES.map((d) => (
              <OpInline key={d} op="device.directive" params={{ device_id: String(device.device_id) }} body={{ type: d }} noReason label={t(locale, `dev.directive.${d}` as MessageKey)} successKey="dev.directive.sent" testId={`directive-${d}`} />
            ))}
          </div>
        ) : null}
        <DataTable columns={dir} rows={directives} rowKey={(d) => d.directive_id} empty={t(locale, "common.empty")} caption={t(locale, "dev.detail.directives")} />
      </Card>
    </div>
  );
}
