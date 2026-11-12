import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { buttonClass } from "@/components/kit/button";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { AuditHistory } from "@/components/admin/audit-history";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { LoyaltyEnrolForm } from "@/components/admin/loyalty-enrol-form";
import { LoyaltyStatusPanel } from "@/components/admin/loyalty-status-panel";
import { Card, PageHeading, one, type SearchParams } from "@/components/admin/kit/page";
import { apiClient, outcome } from "@/lib/api/client";
import { loadGrants } from "@/lib/admin/modules";
import { activationBlocked, canReadLoyalty, canWriteLoyalty, cashValueMtk, guardedPercent, nextProgrammeStatuses, remainingStd, type Enrolment, type ProgrammeVersion } from "@/lib/admin/loyalty";
import { loadEnrolments, loadGifts, loadProgramme, loadProgress, loadVersions } from "@/lib/admin/loyalty-load";
import { channelLabel, groupedDecimal, kindLabel, nameOf, percentText, pointsText, statusLabel, stdText, thresholdText, tierName } from "@/lib/admin/loyalty-view";
import { formatMtkExact } from "@/lib/admin/money";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatBusinessDate, formatDateTime, formatNumber, t } from "@/lib/i18n";

const TONE: Record<"draft" | "active" | "inactive", ChipTone> = { draft: "warning", active: "success", inactive: "quiet" };

// One programme: rules, tiers, channels, status panel, progress (dw aggregates, read only), enrolments, gifts, versions and the audit trail.
export default async function LoyaltyDetailPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<SearchParams> }) {
  const [session, locale, { id }, sp] = await Promise.all([requireSession(), getLocale(), params, searchParams]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canReadLoyalty(session.user.role, grants)) return <Forbidden locale={locale} />;
  const back = <Link href="/admin/loyalty" className="text-body-strong text-accent hover:underline">{t(locale, "loy.back")}</Link>;
  const numeric = /^\d{1,15}$/.test(id) ? Number(id) : null;
  const r = numeric === null ? null : await loadProgramme(session.at, numeric);
  if (!r || !r.ok || r.data === null) {
    const offline = r !== null && !r.ok && r.offline;
    return (
      <div className="space-y-4" data-testid="loyalty-missing">
        <Banner tone={offline ? "offline" : "danger"} role="alert">{t(locale, offline ? "loy.offline" : "loy.not_found")}</Banner>
        {back}
      </div>
    );
  }
  const p = r.data;
  const api = apiClient(session.at);
  const [versions, progress, enrolments, gifts, hist] = await Promise.all([
    loadVersions(session.at, p.programme_id),
    loadProgress(session.at, p.programme_id),
    loadEnrolments(session.at, p.programme_id),
    loadGifts(session.at, p.programme_id),
    outcome(api.GET("/v1/admin/audit", { params: { query: { entity: "programme", entity_id: String(p.programme_id), limit: 20 } } })),
  ]);
  const role = session.user.role;
  const canWrite = canWriteLoyalty(role, grants);
  const moves = nextProgrammeStatuses(p.status, role, grants);
  const tiers = p.tiers ?? [];
  const channels = p.channels ?? [];
  const saved = one(sp.saved);
  const dl = (label: string, value: string) => (
    <div className="min-w-0"><dt className="text-label text-secondary">{label}</dt><dd className="text-body text-primary [overflow-wrap:anywhere]">{value}</dd></div>
  );
  const tierLabel = (code: string | null | undefined): string => {
    const x = tiers.find((tr) => tr.tier_code === code);
    return x ? tierName(locale, x) : code ?? "—";
  };
  const enrolCols: Column<Enrolment>[] = [
    { key: "outlet", header: t(locale, "loy.enrol.col.outlet"), render: (e) => `#${e.outlet_id}` },
    { key: "tier", header: t(locale, "loy.enrol.col.tier"), render: (e) => tierLabel(e.tier_code) },
    { key: "league", header: t(locale, "loy.enrol.col.league"), render: (e) => e.league_label ?? "—" },
    { key: "target", header: t(locale, "loy.enrol.col.target"), align: "right", render: (e) => stdText(locale, e.base_target) },
    { key: "from", header: t(locale, "loy.enrol.col.from"), render: (e) => formatBusinessDate(locale, e.valid_from) },
    { key: "to", header: t(locale, "loy.enrol.col.to"), render: (e) => (e.valid_to ? formatBusinessDate(locale, e.valid_to) : "—") },
  ];
  const prog = progress.ok ? progress.data : null;
  const pointsTotal = prog ? prog.points.earned + prog.points.redeemed + prog.points.expiring : 0;
  const cash = prog && prog.cash_rate_mtk_per_point != null ? cashValueMtk(prog.points.redeemed, prog.cash_rate_mtk_per_point) : null;
  return (
    <div className="space-y-4" data-testid="loyalty-detail-page">
      <PageHeading
        title={t(locale, "loy.detail.title", { code: p.code })}
        caption={<StatusChip tone={TONE[p.status]} testId="programme-status">{statusLabel(locale, p.status)}</StatusChip>}
        actions={<>{back}<Link href="/admin/loyalty/gifts" className={buttonClass("secondary")}>{t(locale, "loy.gifts_link")}</Link>{canWrite && p.status === "draft" ? <Link href={`/admin/loyalty/${p.programme_id}/edit`} className={buttonClass("secondary")} data-testid="programme-edit">{t(locale, "loy.edit")}</Link> : null}</>}
      />
      {saved === "create" || saved === "edit" ? <Banner tone="success" role="status" testId="programme-saved">{saved === "create" ? t(locale, "loy.saved_create") : t(locale, "loy.saved_edit", { n: p.version_no ?? 1 })}</Banner> : null}
      <Card title={nameOf(locale, p)} testId="programme-summary">
        <dl className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {dl(t(locale, "loy.form.name_en"), p.name_en)}
          {dl(t(locale, "loy.form.name_bn"), p.name_bn ?? "—")}
          {dl(t(locale, "loy.col.kind"), kindLabel(locale, p.kind))}
          {dl(t(locale, "loy.form.period"), p.period_label ?? "—")}
          {dl(t(locale, "loy.col.period"), `${formatBusinessDate(locale, p.active_from)} – ${formatBusinessDate(locale, p.active_to)}`)}
          {p.points_expire_on ? dl(t(locale, "loy.detail.expires"), formatBusinessDate(locale, p.points_expire_on)) : null}
          {dl(t(locale, "loy.detail.current", { n: formatNumber(locale, p.version_no ?? 1) }), p.change_reason ?? "—")}
          {p.created_at ? dl(t(locale, "loy.detail.created"), formatDateTime(locale, p.created_at)) : null}
          {p.updated_at ? dl(t(locale, "loy.detail.updated"), formatDateTime(locale, p.updated_at)) : null}
        </dl>
      </Card>
      <Card title={t(locale, "loy.detail.tiers")} testId="programme-tiers">
        {tiers.length === 0 ? <p className="text-body text-secondary" data-testid="programme-tiers-none">{t(locale, "loy.detail.tiers_none")}</p> : (
          <ol className="space-y-1">
            {tiers.map((x) => <li key={x.tier_code} className="flex flex-wrap justify-between gap-2 [overflow-wrap:anywhere]" data-testid={`tier-${x.tier_code}`}><span className="text-body-strong text-primary">{tierName(locale, x)}</span><span className="text-body text-primary">{thresholdText(locale, x)}</span></li>)}
          </ol>
        )}
        <h3 className="text-label text-secondary">{t(locale, "loy.detail.scope")}</h3>
        <p className="text-body text-primary [overflow-wrap:anywhere]" data-testid="programme-channels">{channels.length === 0 ? t(locale, "loy.detail.scope_all") : channels.map((c) => channelLabel(locale, c)).join(", ")}</p>
      </Card>
      {canWrite ? <LoyaltyStatusPanel programmeId={p.programme_id} moves={moves} pastEnd={activationBlocked(p.active_to, businessDate())} superOnlyNote={p.status === "draft" && moves.length === 0} /> : null}
      <Card title={t(locale, "loy.progress.heading")} testId="programme-progress">
        {!progress.ok ? <p role="alert" className="text-body text-secondary" data-testid="progress-failed">{t(locale, progress.offline ? "loy.offline" : "loy.progress.failed")}</p> : prog && prog.tiers.length === 0 ? <p className="text-body text-secondary" data-testid="progress-empty">{t(locale, "loy.progress.empty")}</p> : prog ? (
          <div className="overflow-x-auto">
            <table className="w-full text-body" data-testid="progress-table">
              <caption className="sr-only">{t(locale, "loy.progress.heading")}</caption>
              <thead><tr className="text-label text-secondary">
                <th scope="col" className="py-1 pe-3 text-start">{t(locale, "loy.progress.tier")}</th>
                <th scope="col" className="py-1 pe-3 text-end">{t(locale, "loy.progress.outlets")}</th>
                <th scope="col" className="py-1 pe-3 text-end">{t(locale, "loy.progress.target")}</th>
                <th scope="col" className="py-1 pe-3 text-end">{t(locale, "loy.progress.achieved")}</th>
                <th scope="col" className="py-1 pe-3 text-end">{t(locale, "loy.progress.remaining")}</th>
                <th scope="col" className="py-1 text-end">{t(locale, "loy.progress.percent")}</th>
              </tr></thead>
              <tbody>
                {prog.tiers.map((x) => {
                  const rem = remainingStd(x.achieved_std, x.target_std);
                  return (
                    <tr key={x.tier_code} data-testid={`progress-${x.tier_code}`}>
                      <th scope="row" className="py-1 pe-3 text-start text-body-strong text-primary">{tierLabel(x.tier_code)}</th>
                      <td className="py-1 pe-3 text-end tabular-nums">{formatNumber(locale, x.enrolled_outlets)}</td>
                      <td className="py-1 pe-3 text-end tabular-nums">{stdText(locale, x.target_std)}</td>
                      <td className="py-1 pe-3 text-end tabular-nums">{stdText(locale, x.achieved_std)}</td>
                      <td className="py-1 pe-3 text-end tabular-nums">{rem === null ? "—" : t(locale, "loy.std_value", { value: groupedDecimal(locale, rem) })}</td>
                      <td className="py-1 text-end text-body-strong tabular-nums text-primary" data-testid={`progress-pct-${x.tier_code}`}>{percentText(locale, guardedPercent(x.achieved_std, x.target_std))}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : null}
        {prog ? (
          <>
            <h3 className="text-label text-secondary">{t(locale, "loy.progress.points_heading")}</h3>
            {pointsTotal === 0 ? <p className="text-body text-secondary" data-testid="points-empty">{t(locale, "loy.progress.points_empty")}</p> : (
              <dl className="grid gap-4 md:grid-cols-3" data-testid="points">
                {dl(t(locale, "loy.progress.earned"), pointsText(locale, prog.points.earned))}
                {dl(t(locale, "loy.progress.redeemed"), pointsText(locale, prog.points.redeemed))}
                {dl(t(locale, "loy.progress.expiring"), pointsText(locale, prog.points.expiring))}
                {cash !== null ? dl(t(locale, "loy.progress.cash"), t(locale, "loy.cash_value", { amount: formatMtkExact(locale, cash) })) : null}
              </dl>
            )}
          </>
        ) : null}
      </Card>
      <Card title={t(locale, "loy.enrol.heading")} testId="programme-enrolments">
        {!enrolments.ok ? <p role="alert" className="text-body text-secondary" data-testid="enrol-failed">{t(locale, "loy.enrol.failed")}</p> : <DataTable columns={enrolCols} rows={enrolments.data} rowKey={(e) => `${e.outlet_id}:${e.valid_from}`} empty={t(locale, "loy.enrol.empty")} caption={t(locale, "loy.enrol.heading")} />}
        {canWrite ? (p.status === "inactive" ? <p className="text-body text-secondary" data-testid="enrol-ended">{t(locale, "loy.enrol.ended")}</p> : <LoyaltyEnrolForm programmeId={p.programme_id} tiers={tiers} />) : null}
      </Card>
      <Card title={t(locale, "loy.gifts.title")} testId="programme-gifts">
        {!gifts.ok ? <p role="alert" className="text-body text-secondary">{t(locale, "loy.gifts.failed")}</p> : gifts.data.length === 0 ? <p className="text-body text-secondary">{t(locale, "loy.gifts.empty")}</p> : (
          <ul className="space-y-1">
            {gifts.data.map((g) => <li key={g.gift_id} className="flex flex-wrap justify-between gap-2 [overflow-wrap:anywhere]">{locale === "bn" && g.name_bn ? g.name_bn : g.name_en}<span>{g.points_cost === null ? t(locale, "loy.gifts.points_none") : pointsText(locale, g.points_cost)}</span></li>)}
          </ul>
        )}
      </Card>
      <Card title={t(locale, "loy.detail.versions")} testId="programme-versions" data>
        {!versions.ok ? <p role="alert" className="text-body text-secondary" data-testid="versions-failed">{t(locale, "loy.detail.versions_failed")}</p> : versions.data.length === 0 ? <p className="text-body text-secondary">{t(locale, "loy.detail.versions_empty")}</p> : (
          <ol className="space-y-3">
            {versions.data.map((v: ProgrammeVersion) => (
              <li key={v.programme_version_id} className="border-l-2 border-divider ps-3" data-testid="programme-version">
                <p className="text-body-strong text-primary">{t(locale, "loy.detail.version_n", { n: formatNumber(locale, v.version_no) })}{v.is_current ? ` · ${t(locale, "loy.detail.is_current")}` : ""} · <time dateTime={v.created_at}>{formatDateTime(locale, v.created_at)}</time></p>
                <p className="text-body text-secondary [overflow-wrap:anywhere]">{nameOf(locale, v)} · {formatBusinessDate(locale, v.active_from)} – {formatBusinessDate(locale, v.active_to)} · {t(locale, "loy.col.tiers")}: {formatNumber(locale, v.tiers.length)}</p>
                {v.change_reason ? <p className="text-body text-primary [overflow-wrap:anywhere]">{t(locale, "loy.detail.reason")}: {v.change_reason}</p> : null}
              </li>
            ))}
          </ol>
        )}
      </Card>
      <AuditHistory entries={hist.ok ? hist.data.items : []} failed={!hist.ok} locale={locale} />
    </div>
  );
}
