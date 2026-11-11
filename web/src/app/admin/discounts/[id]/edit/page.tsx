import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { OfferFormView } from "@/components/admin/offer-form";
import { PageHeading } from "@/components/admin/kit/page";
import { apiClient, outcome } from "@/lib/api/client";
import { loadGrants } from "@/lib/admin/modules";
import { canWriteOffers, formFromOffer } from "@/lib/admin/offers";
import { loadOfferRefs } from "@/lib/admin/offers-load";
import { qualifyingUnit, rewardUnit } from "@/lib/admin/offers-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Edit a draft programme: every save appends an immutable version (updateOffer). Only a draft can be edited (409 otherwise).
export default async function EditDiscountPage({ params }: { params: Promise<{ id: string }> }) {
  const [session, locale, { id }] = await Promise.all([requireSession(), getLocale(), params]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canWriteOffers(session.user.role, grants)) return <Forbidden locale={locale} />;
  const back = `/admin/discounts/${id}`;
  const r = /^\d{1,15}$/.test(id) ? await outcome(apiClient(session.at).GET("/v1/admin/offers/{id}", { params: { path: { id: Number(id) } } })) : null;
  if (!r || !r.ok) {
    return <Banner tone={r && r.status >= 500 ? "offline" : "danger"} role="alert" testId="discount-missing">{t(locale, r && r.status >= 500 ? "disc.offline" : "disc.not_found")} <Link href="/admin/discounts" className="underline">{t(locale, "disc.back")}</Link></Banner>;
  }
  const offer = r.data;
  if (offer.status !== "draft") {
    return <Banner tone="warning" role="alert" testId="discount-not-draft">{t(locale, "disc.draft_only")} <Link href={back} className="underline">{t(locale, "disc.back_detail")}</Link></Banner>;
  }
  const refs = await loadOfferRefs(session.at);
  // An inactive SKU is not in the active SKU list: keep its unit from the offer so the entered quantities stay in the same unit.
  for (const u of offer.sku_units ?? []) if (!refs.skus.some((x) => x.id === u.sku_id)) refs.skus.push({ id: u.sku_id, label: `#${u.sku_id}`, unit: u });
  const units = { qualifying: qualifyingUnit(offer, offer.sku_units), reward: rewardUnit(offer, offer.sku_units) };
  const etag = r.response.headers.get("etag")?.match(/\d+/)?.[0];
  const version = etag ? Number(etag) : offer.version;
  return (
    <div className="space-y-4" data-testid="discount-edit-page">
      <PageHeading title={t(locale, "disc.edit.title", { code: offer.code })} actions={<Link href={back} className="text-body-strong text-accent hover:underline">{t(locale, "disc.back_detail")}</Link>} />
      {refs.failed ? <Banner tone="warning" role="alert" testId="disc-refs-failed">{t(locale, "error.ref_load")}</Banner> : null}
      <OfferFormView mode="edit" offerId={offer.id} version={version} initial={formFromOffer(offer, units)} skus={refs.skus} productNodes={refs.productNodes} nodes={refs.nodes} backHref={back} />
    </div>
  );
}
