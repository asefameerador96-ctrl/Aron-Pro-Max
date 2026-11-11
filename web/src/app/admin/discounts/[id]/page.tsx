import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { buttonClass } from "@/components/kit/button";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { AuditHistory } from "@/components/admin/audit-history";
import { OfferStatusPanel } from "@/components/admin/offer-status-panel";
import { Card, PageHeading, one, type SearchParams } from "@/components/admin/kit/page";
import { apiClient, outcome } from "@/lib/api/client";
import { loadGrants } from "@/lib/admin/modules";
import { activationBlocked, canReadOffers, canWriteOffers, nextStatuses, type Offer, type OfferVersion } from "@/lib/admin/offers";
import { loadOfferRefs } from "@/lib/admin/offers-load";
import { statusLabel, titleOf, typeLabel, valueText } from "@/lib/admin/offers-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatBusinessDate, formatDateTime, t, type MessageKey } from "@/lib/i18n";

const TONE: Record<Offer["status"], ChipTone> = { draft: "warning", active: "success", inactive: "quiet" };

// One programme: current rule, products, scope, versions (listOfferVersions) and the status trail (the audit log of the offer).
export default async function DiscountDetailPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<SearchParams> }) {
  const [session, locale, { id }, sp] = await Promise.all([requireSession(), getLocale(), params, searchParams]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canReadOffers(session.user.role, grants)) return <Forbidden locale={locale} />;
  const api = apiClient(session.at);
  const numeric = /^\d{1,15}$/.test(id) ? Number(id) : null;
  const r = numeric === null ? null : await outcome(api.GET("/v1/admin/offers/{id}", { params: { path: { id: numeric } } }));
  const back = <Link href="/admin/discounts" className="text-body-strong text-accent hover:underline">{t(locale, "disc.back")}</Link>;
  if (!r || !r.ok) {
    const offline = r !== null && (r.status >= 500 || r.status === 0);
    return (
      <div className="space-y-4" data-testid="discount-missing">
        <Banner tone={offline ? "offline" : "danger"} role="alert">{t(locale, offline ? "disc.offline" : "disc.not_found")}</Banner>
        {back}
      </div>
    );
  }
  const offer = r.data;
  const [versions, hist, refs] = await Promise.all([
    outcome(api.GET("/v1/admin/offers/{id}/versions", { params: { path: { id: offer.id }, query: { limit: 50 } } })),
    outcome(api.GET("/v1/admin/audit", { params: { query: { entity: "offer", entity_id: String(offer.id), limit: 20 } } })),
    loadOfferRefs(session.at, { skus: true, productLevels: ["category", "brand", "variant"], nodeTypes: [...new Set(offer.scope.map((s) => s.node_type))].filter((x) => ["wing", "division", "territory", "zone", "route"].includes(x)) }),
  ]);
  const productLabel = (level: string, pid: number): string => {
    if (level === "sku") return refs.skus.find((s) => s.id === pid)?.label ?? `#${pid}`;
    return refs.productNodes[level as "category" | "brand" | "variant"]?.find((o) => o.value === String(pid))?.label ?? `#${pid}`;
  };
  const nodeLabel = (type: string, nid: number): string => (refs.nodes as Record<string, { value: string; label: string }[]>)[type]?.find((o) => o.value === String(nid))?.label ?? `#${nid}`;
  const role = session.user.role;
  const canWrite = canWriteOffers(role, grants);
  const moves = nextStatuses(offer.status, role, grants);
  const today = businessDate();
  const saved = one(sp.saved);
  const rewardName = offer.reward_sku_id != null ? productLabel("sku", offer.reward_sku_id) : null;
  const dl = (label: string, value: string) => (
    <div className="min-w-0"><dt className="text-label text-secondary">{label}</dt><dd className="text-body text-primary [overflow-wrap:anywhere]">{value}</dd></div>
  );
  return (
    <div className="space-y-4" data-testid="discount-detail-page">
      <PageHeading
        title={t(locale, "disc.detail.title", { code: offer.code })}
        caption={<StatusChip tone={TONE[offer.status]} testId="offer-status">{statusLabel(locale, offer.status)}</StatusChip>}
        actions={<>{back}{canWrite && offer.status === "draft" ? <Link href={`/admin/discounts/${offer.id}/edit`} className={buttonClass("secondary")} data-testid="offer-edit">{t(locale, "disc.edit")}</Link> : null}</>}
      />
      {saved === "create" || saved === "edit" ? <Banner tone="success" role="status" testId="offer-saved">{saved === "create" ? t(locale, "disc.saved_create") : t(locale, "disc.saved_edit", { n: offer.version_no ?? 1 })}</Banner> : null}
      <Card title={`${titleOf(locale, offer)}`} testId="offer-summary">
        <dl className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {dl(t(locale, "disc.form.title_en"), offer.title_en)}
          {dl(t(locale, "disc.form.title_bn"), offer.title_bn ?? "—")}
          {dl(t(locale, "disc.detail.campaign"), offer.group_code)}
          {dl(t(locale, "disc.col.type"), typeLabel(locale, offer.offer_type))}
          {dl(t(locale, "disc.form.level"), t(locale, `disc.level.${offer.level}` as MessageKey))}
          {dl(t(locale, "disc.form.stacking"), t(locale, `disc.stacking.${offer.stacking}` as MessageKey))}
          {dl(t(locale, "disc.col.valid"), `${formatBusinessDate(locale, offer.valid_from)} – ${formatBusinessDate(locale, offer.valid_to)}`)}
          {dl(t(locale, "disc.detail.current", { n: offer.version_no ?? 1 }), offer.change_reason ?? "—")}
          {offer.created_at ? dl(t(locale, "disc.detail.created"), formatDateTime(locale, offer.created_at)) : null}
          {offer.updated_at ? dl(t(locale, "disc.detail.updated"), formatDateTime(locale, offer.updated_at)) : null}
        </dl>
      </Card>
      <Card title={t(locale, "disc.detail.rules")} testId="offer-rule">
        <p className="text-body-strong text-primary [overflow-wrap:anywhere]" data-testid="offer-value">{valueText(locale, offer)}</p>
        {rewardName ? <p className="text-body text-secondary [overflow-wrap:anywhere]">{t(locale, "disc.form.reward_sku")}: {rewardName}</p> : null}
        <h3 className="text-label text-secondary">{t(locale, "disc.detail.products")}</h3>
        {offer.qualifying.length === 0 ? <p className="text-body text-secondary">{t(locale, "disc.form.products_none")}</p> : (
          <ul className="space-y-1" data-testid="offer-products">
            {offer.qualifying.map((q) => <li key={`${q.product_level}:${q.product_id}`} className="[overflow-wrap:anywhere]">{t(locale, `disc.plevel.${q.product_level}` as MessageKey)} · {productLabel(q.product_level, q.product_id)}</li>)}
          </ul>
        )}
        <h3 className="text-label text-secondary">{t(locale, "disc.detail.scope")}</h3>
        {offer.scope.length === 0 ? <p className="text-body text-secondary" data-testid="offer-scope">{t(locale, "disc.form.scope_none")}</p> : (
          <ul className="space-y-1" data-testid="offer-scope">
            {offer.scope.map((s) => <li key={`${s.node_type}:${s.node_id}`} className="[overflow-wrap:anywhere]">{t(locale, `disc.node.${s.node_type}` as MessageKey)} · {nodeLabel(s.node_type, s.node_id)}</li>)}
          </ul>
        )}
      </Card>
      {canWrite ? <OfferStatusPanel offerId={offer.id} moves={moves} pastEnd={activationBlocked(offer.valid_to, today)} superOnlyNote={offer.status === "draft" && moves.length === 0} /> : null}
      <Card title={t(locale, "disc.detail.versions")} testId="offer-versions" data>
        {!versions.ok ? <p role="alert" className="text-body text-secondary" data-testid="versions-failed">{t(locale, "disc.detail.versions_failed")}</p> : versions.data.items.length === 0 ? <p className="text-body text-secondary">{t(locale, "disc.detail.versions_empty")}</p> : (
          <ol className="space-y-3">
            {versions.data.items.map((v: OfferVersion) => (
              <li key={v.offer_version_id} className="border-l-2 border-divider ps-3" data-testid="offer-version">
                <p className="text-body-strong text-primary">{t(locale, "disc.detail.version_n", { n: v.version_no })}{v.is_current ? ` · ${t(locale, "disc.detail.is_current")}` : ""} · <time dateTime={v.created_at}>{formatDateTime(locale, v.created_at)}</time></p>
                <p className="text-body text-secondary [overflow-wrap:anywhere]">{valueText(locale, v)} · {formatBusinessDate(locale, v.valid_from)} – {formatBusinessDate(locale, v.valid_to)}</p>
                {v.change_reason ? <p className="text-body text-primary [overflow-wrap:anywhere]">{t(locale, "disc.detail.reason")}: {v.change_reason}</p> : null}
              </li>
            ))}
          </ol>
        )}
      </Card>
      <AuditHistory entries={hist.ok ? hist.data.items : []} failed={!hist.ok} locale={locale} />
    </div>
  );
}
