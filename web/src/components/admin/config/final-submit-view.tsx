// F-WEB-051 Web Final Submit: preview of a zone-day, back-date banner, DSS advisory, routes with Delete Section Data, Submit.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { GeoCascade, type CascadeLevel } from "@/components/admin/kit/geo-cascade";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { GEO_LEVELS, type GeoLevel, type GeoNode } from "@/lib/admin/geo";
import { formatMtk } from "@/lib/admin/money";
import type { FinalSubmitPreview } from "@/lib/admin/types";
import { formatBusinessDate, formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

type RouteRow = FinalSubmitPreview["routes"][number];
const TEAM = "/api/bff/team-op";

export interface FinalSubmitViewProps {
  locale: Locale;
  options: Record<GeoLevel, GeoNode[]>;
  selection: Partial<Record<GeoLevel, string>>;
  date: string;
  today: string;
  preview: FinalSubmitPreview | null;
  canSubmit: boolean;
  canVoid: boolean;
}

export function FinalSubmitView({ locale, options, selection, date, today, preview, canSubmit, canVoid }: FinalSubmitViewProps) {
  const levels: CascadeLevel[] = GEO_LEVELS.map((l) => ({ param: l, label: t(locale, `otp.level.${l}` as MessageKey), value: selection[l] ?? "", options: options[l].map((n) => ({ value: String(n.id), label: locale === "bn" && n.name_bn ? n.name_bn : n.name })) }));
  const backdated = date < today;
  const columns: Column<RouteRow>[] = [
    { key: "route", header: t(locale, "fs.col.route"), render: (r) => `${r.route_code} · ${r.route_name}` },
    { key: "ff", header: t(locale, "fs.col.ff"), render: (r) => r.ff_user_name ?? t(locale, "fs.sr_not_set") },
    { key: "state", header: t(locale, "cfgc.col.status"), render: (r) => t(locale, `fs.state.${r.state}` as MessageKey) },
    { key: "memos", header: t(locale, "fs.col.memos"), render: (r) => formatNumber(locale, r.memo_count), align: "right" },
    { key: "net", header: t(locale, "fs.col.net"), render: (r) => formatMtk(locale, r.net_mtk), align: "right" },
    { key: "pend", header: t(locale, "sh.col.pending"), render: (r) => (r.pending_rows_reported == null ? "—" : formatNumber(locale, r.pending_rows_reported)), align: "right" },
    {
      key: "void",
      header: t(locale, "common.actions"),
      render: (r) =>
        canVoid && preview && !preview.already_submitted && r.had_data ? (
          <OpInline op="day.data-void" endpoint={TEAM} uuidMembers={["client_uuid"]} body={{ route_id: r.route_id, business_date: preview.business_date, scope: "web_entry" }} label={t(locale, "fs.void")} danger successKey="fs.void.done" testId={`void-${r.route_id}`} />
        ) : null,
    },
  ];
  const fields: OpFieldDef[] = [];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "fs.title")} intro={t(locale, "fs.intro")} />
      <GeoCascade levels={levels} viewLabel={t(locale, "fs.get")} allLabel={t(locale, "common.all")} action="/final-submit/submit" extra={[{ name: "date", value: date, label: t(locale, "cfgc.col.date"), type: "date" }]} />
      {backdated ? (
        <p role="status" data-testid="backdate-banner" className="rounded border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] p-3 text-sm text-[var(--warning)]">
          {t(locale, "fs.backdate", { date: formatBusinessDate(locale, date) })}
        </p>
      ) : null}
      {preview === null ? (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "fs.choose")}</p>
      ) : (
        <>
          {preview.already_submitted ? (
            <p role="alert" data-testid="already-submitted" className="rounded border border-[var(--danger)] bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-sm text-[var(--danger)]">
              {t(locale, "fs.already", { by: preview.submitted_by ?? "—", at: preview.submitted_at ? formatDateTime(locale, preview.submitted_at) : "—" })}
            </p>
          ) : null}
          <div role="note" data-testid="dss-advisory" className="rounded border border-slate-300 bg-white p-3 text-sm">
            <p className="font-semibold">{t(locale, "fs.dss.title")}</p>
            <ul className="list-disc pl-5">
              {(preview.warnings ?? []).map((w) => (
                <li key={w}>{t(locale, `fs.warn.${w}` as MessageKey)}</li>
              ))}
            </ul>
            <p className="mt-1 text-slate-600">{t(locale, "fs.dss.hint")}</p>
          </div>
          <Card title={`${formatBusinessDate(locale, preview.business_date)}`}>
            <DataTable columns={columns} rows={preview.routes} rowKey={(r) => String(r.route_id)} empty={t(locale, "common.empty")} caption={t(locale, "fs.title")} />
          </Card>
          {canSubmit && !preview.already_submitted ? (
            <Card title={t(locale, "fs.submit.title")}>
              <p className="text-sm text-slate-600">{t(locale, "fs.submit.hint")}</p>
              <OpForm op="day.final-submit" endpoint={TEAM} uuidMembers={["client_uuid"]} confirmText={t(locale, "fs.confirm")} fixed={{ zone_id: preview.zone_id, business_date: preview.business_date }} fields={fields} noReason submitLabel={t(locale, "fs.submit")} successKey="fs.submit.done" resetOnSuccess={false} testId="final-submit-form" />
            </Card>
          ) : null}
          <p className="text-xs text-slate-500">
            <Link href="/final-submit/submit" className="underline">{t(locale, "common.clear")}</Link>
          </p>
        </>
      )}
    </div>
  );
}
