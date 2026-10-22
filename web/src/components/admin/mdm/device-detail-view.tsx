// D-MDM-01 device detail: facts from the latest status report, flags, policy, actions, commands and the activity log.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { MdmActivityEntry, MdmCommand, MdmDeviceDetail, MdmLevel } from "@/lib/admin/mdm";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { CommandPanel } from "./command-panel";
import { PolicyAssign } from "./policy-assign";
import { FlagChips, MdmTabs, usersText } from "./shared";

const yesNo = (locale: Locale, v: boolean) => t(locale, v ? "common.yes" : "common.no");
const when = (locale: Locale, iso?: string | null) => (iso ? <time dateTime={iso}>{formatDateTime(locale, iso)}</time> : "—");

export function commandColumns(locale: Locale, level: MdmLevel, me: string): Column<MdmCommand>[] {
  return [
    { key: "type", header: t(locale, "mdm.col.command"), render: (c) => t(locale, `mdm.cmd.${c.type}` as MessageKey) },
    { key: "state", header: t(locale, "dev.col.state"), render: (c) => <span data-testid={`cmd-state-${c.command_id}`}>{t(locale, `mdm.cmdstate.${c.state}` as MessageKey)}</span> },
    { key: "reason", header: t(locale, "mdm.col.reason"), render: (c) => c.reason },
    { key: "by", header: t(locale, "mdm.col.by"), render: (c) => c.created_by ?? "—" },
    { key: "appr", header: t(locale, "mdm.col.approver"), render: (c) => c.approved_by ?? "—" },
    { key: "at", header: t(locale, "mdm.col.when"), render: (c) => when(locale, c.created_at) },
    { key: "ack", header: t(locale, "mdm.col.acked"), render: (c) => when(locale, c.acked_at) },
    {
      key: "act",
      header: t(locale, "common.actions"),
      render: (c) =>
        level === "w" && c.state === "pending_approval" ? (
          c.created_by === me ? (
            <span className="text-xs text-slate-600" data-testid={`cmd-wait-${c.command_id}`}>
              {t(locale, "mdm.cmd.wait_other")}
            </span>
          ) : (
            <div className="flex flex-wrap gap-2">
              <OpInline op="mdm.decide" params={{ command_id: c.command_id }} body={{ decision: "approve" }} label={t(locale, "mdm.cmd.approve")} successKey="mdm.cmd.decided" testId={`cmd-approve-${c.command_id}`} />
              <OpInline op="mdm.decide" params={{ command_id: c.command_id }} body={{ decision: "reject" }} label={t(locale, "mdm.cmd.reject")} danger successKey="mdm.cmd.decided" testId={`cmd-reject-${c.command_id}`} />
            </div>
          )
        ) : null,
    },
  ];
}

export function activityColumns(locale: Locale, withDevice: boolean): Column<MdmActivityEntry>[] {
  const cols: Column<MdmActivityEntry>[] = [
    { key: "at", header: t(locale, "mdm.col.when"), render: (e) => when(locale, e.at) },
    { key: "kind", header: t(locale, "mdm.col.kind"), render: (e) => t(locale, `mdm.kind.${e.kind}` as MessageKey) },
  ];
  if (withDevice) cols.push({ key: "dev", header: t(locale, "mdm.col.device"), render: (e) => (e.device_id ? <Link href={`/admin/mdm/devices/${e.device_id}`} className="text-brand-700 underline">{`#${e.device_id}`}</Link> : "—") });
  cols.push(
    { key: "sum", header: t(locale, "mdm.col.summary"), render: (e) => e.summary },
    { key: "by", header: t(locale, "mdm.col.by"), render: (e) => e.actor ?? "—" },
    { key: "why", header: t(locale, "mdm.col.reason"), render: (e) => e.reason ?? "—" },
  );
  return cols;
}

export function MdmDeviceDetailView({ locale, detail, activity, policies, level, me }: { locale: Locale; detail: MdmDeviceDetail; activity: MdmActivityEntry[]; policies: { id: number; name: string }[]; level: MdmLevel; me: string }) {
  const d = detail.device;
  const r = detail.last_report;
  const fact = (label: string, value: React.ReactNode, id?: string) => (
    <div className="space-y-0.5" key={label}>
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="break-words text-sm font-medium" data-testid={id}>
        {value}
      </dd>
    </div>
  );
  return (
    <div className="space-y-4">
      <PageHeading title={`${t(locale, "mdm.detail.title")} · ${usersText(d.users)}`} intro={`${t(locale, `dev.flavour.${d.flavour}` as MessageKey)} · ${d.device_uuid}`} />
      <MdmTabs locale={locale} active="devices" />
      <Card title={t(locale, "mdm.detail.summary")} testId="mdm-summary">
        <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {fact(t(locale, "mdm.col.enrolment"), t(locale, `dev.state.${d.status}` as MessageKey), "mdm-status")}
          {fact(t(locale, "mdm.col.owner"), t(locale, d.device_owner ? "mdm.owner.yes" : "mdm.owner.no"))}
          {fact(t(locale, "dev.col.trust"), `${t(locale, `dev.trust.${detail.trust_level}` as MessageKey)} (${t(locale, `dev.integrity.${detail.integrity_verdict}` as MessageKey)})`)}
          {fact(t(locale, "mdm.col.app"), `${d.app_version ?? "—"}${d.latest_app_version ? ` → ${d.latest_app_version}` : ""}`)}
          {fact(t(locale, "dev.col.model"), d.model ?? "—")}
          {fact(t(locale, "dev.col.last_seen"), when(locale, d.last_contact_at))}
          {fact(t(locale, "mdm.col.battery"), d.battery_pct == null ? "—" : `${formatNumber(locale, d.battery_pct)}%`)}
          {fact(t(locale, "mdm.col.network"), d.network ? t(locale, `mdm.net.${d.network}` as MessageKey) : "—")}
          {fact(t(locale, "mdm.col.enrolled"), when(locale, detail.enrolled_at))}
          {fact(t(locale, "mdm.col.policy"), d.policy_name ?? t(locale, "mdm.policy.none"))}
        </dl>
        <div className="space-y-1">
          <h3 className="text-sm font-semibold">{t(locale, "mdm.col.flags")}</h3>
          <FlagChips locale={locale} flags={d.compliance_flags} />
        </div>
      </Card>
      {r ? (
        <Card title={t(locale, "mdm.detail.report")} testId="mdm-report">
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {fact(t(locale, "mdm.report.at"), when(locale, r.reported_at))}
            {fact(t(locale, "mdm.report.lockdown"), r.lockdown_level_applied)}
            {fact(t(locale, "mdm.report.policy_applied"), r.policy_version_applied == null ? "—" : formatNumber(locale, r.policy_version_applied, { useGrouping: false }))}
            {fact(t(locale, "mdm.report.blocking"), yesNo(locale, r.blocking_active))}
            {fact(t(locale, "mdm.report.location"), yesNo(locale, r.location_enabled))}
            {fact(t(locale, "mdm.report.devopts"), yesNo(locale, r.dev_options_enabled))}
            {fact(t(locale, "mdm.report.adb"), yesNo(locale, r.adb_enabled))}
            {fact(t(locale, "mdm.report.autotime"), yesNo(locale, r.auto_time_enabled))}
            {fact(t(locale, "mdm.report.mock"), r.mock_location_apps.length ? r.mock_location_apps.join(", ") : t(locale, "common.none"))}
            {fact(t(locale, "mdm.report.pending"), formatNumber(locale, r.pending_rows))}
          </dl>
        </Card>
      ) : (
        <p className="rounded border border-dashed border-slate-300 bg-white p-4 text-slate-600" data-testid="mdm-no-report">
          {t(locale, "mdm.detail.no_report")}
        </p>
      )}
      {level === "w" ? (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card title={t(locale, "mdm.detail.actions")} testId="mdm-actions-card">
            <CommandPanel deviceId={d.device_id} />
          </Card>
          <Card title={t(locale, "mdm.detail.policy")}>
            <PolicyAssign deviceId={d.device_id} current={d.policy_id ?? null} policies={policies} />
          </Card>
        </div>
      ) : (
        <p className="text-xs text-slate-600" data-testid="mdm-readonly">
          {t(locale, "mdm.readonly")}
        </p>
      )}
      <Card title={t(locale, "mdm.detail.commands")}>
        <DataTable columns={commandColumns(locale, level, me)} rows={detail.recent_commands} rowKey={(c) => c.command_id} empty={t(locale, "common.empty")} caption={t(locale, "mdm.detail.commands")} />
      </Card>
      <Card title={t(locale, "mdm.detail.activity")} testId="mdm-activity">
        <DataTable columns={activityColumns(locale, false)} rows={activity} rowKey={(e) => `${e.at}-${e.kind}-${e.summary}`} empty={t(locale, "common.empty")} caption={t(locale, "mdm.detail.activity")} />
      </Card>
    </div>
  );
}
