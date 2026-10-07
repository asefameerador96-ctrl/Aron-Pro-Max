// N-045 enrolment page: QR generator, active codes, and the enrolled-device list with compliance.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { Device, EnrolmentToken } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { EnrolmentForm } from "./enrolment-form";

/** A phone is compliant when it is the device owner and runs the current policy; otherwise say what is missing. */
export function complianceOf(d: Device, currentPolicy: number | null): "ok" | "not_owner" | "policy_behind" {
  if (!d.device_owner) return "not_owner";
  if (currentPolicy !== null && (d.policy_version_applied ?? -1) < currentPolicy) return "policy_behind";
  return "ok";
}

export function EnrolmentView({ locale, tokens, devices, currentPolicy, canWrite }: { locale: Locale; tokens: EnrolmentToken[]; devices: Device[]; currentPolicy: number | null; canWrite: boolean }) {
  const tokenCols: Column<EnrolmentToken>[] = [
    { key: "p", header: t(locale, "enr.col.prefix"), render: (k) => <code>{k.token_prefix}…</code> },
    { key: "f", header: t(locale, "dev.col.flavour"), render: (k) => t(locale, `dev.flavour.${k.flavour}` as MessageKey) },
    { key: "z", header: t(locale, "enr.zone_col"), render: (k) => (k.zone_id ? formatNumber(locale, k.zone_id, { useGrouping: false }) : "—") },
    { key: "u", header: t(locale, "enr.col.used"), render: (k) => `${formatNumber(locale, k.used_count)} / ${formatNumber(locale, k.max_uses)}`, align: "right" },
    { key: "e", header: t(locale, "enr.col.expires"), render: (k) => formatDateTime(locale, k.expires_at) },
    { key: "a", header: t(locale, "common.actions"), render: (k) => (canWrite && !k.revoked_at ? <OpInline op="enrolment.revoke" params={{ token_id: String(k.token_id) }} noReason label={t(locale, "enr.revoke")} danger successKey="enr.revoked" testId={`revoke-token-${k.token_id}`} /> : null) },
  ];
  const devCols: Column<Device>[] = [
    { key: "u", header: t(locale, "dev.col.users"), render: (d) => d.bound_users.map((u) => u.username).join(", ") || "—" },
    { key: "m", header: t(locale, "dev.col.model"), render: (d) => (d.device_info ? `${d.device_info.manufacturer} ${d.device_info.model}` : "—") },
    { key: "p", header: t(locale, "enr.col.policy"), render: (d) => (d.policy_version_applied == null ? "—" : formatNumber(locale, d.policy_version_applied, { useGrouping: false })) },
    { key: "c", header: t(locale, "enr.col.compliance"), render: (d) => t(locale, ({ ok: "enr.compliant", not_owner: "enr.not_owner", policy_behind: "enr.policy_behind" } as const)[complianceOf(d, currentPolicy)]) },
    { key: "s", header: t(locale, "dev.col.state"), render: (d) => t(locale, `dev.state.${d.status}` as MessageKey) },
    { key: "l", header: t(locale, "dev.col.last_seen"), render: (d) => (d.last_contact_at ? formatDateTime(locale, d.last_contact_at) : t(locale, "dev.never")) },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "enr.title")} intro={t(locale, "enr.intro")} />
      {canWrite ? <EnrolmentForm /> : null}
      <Card title={t(locale, "enr.tokens")}>
        <DataTable columns={tokenCols} rows={tokens} rowKey={(k) => String(k.token_id)} empty={t(locale, "common.empty")} caption={t(locale, "enr.tokens")} />
      </Card>
      <Card title={t(locale, "enr.devices")}>
        <DataTable columns={devCols} rows={devices} rowKey={(d) => String(d.device_id)} empty={t(locale, "common.empty")} caption={t(locale, "enr.devices")} />
      </Card>
    </div>
  );
}
