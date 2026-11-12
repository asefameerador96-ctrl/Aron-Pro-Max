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
import { activationBlocked, canReadLoyalty, canWriteLoyalty, nextProgrammeStatuses, type Enrolment, type ProgrammeVersion } from "@/lib/admin/loyalty";
import { loadEnrolments, loadGifts, loadProgramme, loadVersions } from "@/lib/admin/loyalty-load";
import { kindLabel, nameOf, pointsText, qualifyingLabel, scopeLabel, statusLabel, stdText, thresholdText, tierName } from "@/lib/admin/loyalty-view";
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
  if (!r || !r.ok) {
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
  const [versions, enrolments, gifts, hist] = await Promise.all([
    loadVersions(session.at, p.programme_id),
    loadEnrolments(session.at, p.programme_id),
    loadGifts(session.at, p.programme_id),
    outcome(api.GET("/v1/admin/audit", { params: { query: { entity: "programme", entity_id: String(p.programme_id), limit: 20 } } })),
  ]);
  const role = session.user.role;
  const canWrite = canWriteLoyalty(role, grants);
  const moves = nextProgrammeStatuses(p.status, role, grants);
  const tiers = p.tiers ?? [];
  const scope = p.scope ?? [];
  const qualifying = p.qualifying ?? [];
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
            {[...tiers].sort((a, b) => a.sort_order - b.sort_order).map((x) => <li key={x.tier_code} className="flex flex-wrap justify-between gap-2 [overflow-wrap:anywhere]" data-testid={`tier-${x.tier_code}`}><span className="text-body-strong text-primary">{tierName(locale, x)}</span><span className="text-body text-primary">{thresholdText(locale, x)}</span></li>)}
          </ol>
        )}
        <h3 className="text-label text-secondary">{t(locale, "loy.detail.scope")}</h3>
        <p className="text-body text-primary [overflow-wrap:anywhere]" data-testid="programme-scope">{scope.length === 0 ? t(locale, "loy.detail.scope_all") : scope.map((n) => scopeLabel(locale, n)).join(", ")}</p>
        <h3 className="text-label text-secondary">{t(locale, "loy.detail.qualifying")}</h3>
        <p className="text-body text-primary [overflow-wrap:anywhere]" data-testid="programme-qualifying">{qualifying.length === 0 ? t(locale, "loy.detail.qualifying_all") : qualifying.map((q) => qualifyingLabel(locale, q)).join(", ")}</p>
        {canWrite && p.status === "draft" ? <p className="text-caption text-secondary">{t(locale, "loy.detail.scope_kept")}</p> : null}
      </Card>
      {canWrite ? <LoyaltyStatusPanel programmeId={p.programme_id} moves={moves} pastEnd={activationBlocked(p.active_to, businessDate())} superOnlyNote={p.status === "draft" && moves.length === 0} /> : null}
      <Card title={t(locale, "loy.progress.heading")} testId="programme-progress">
        <p role="note" className="text-body text-secondary" data-testid="progress-unavailable">{t(locale, "loy.progress.unavailable")}</p>
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
