import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { OfferFormView } from "@/components/admin/offer-form";
import { PageHeading } from "@/components/admin/kit/page";
import { loadGrants } from "@/lib/admin/modules";
import { canWriteOffers, emptyForm } from "@/lib/admin/offers";
import { loadOfferRefs } from "@/lib/admin/offers-load";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Create a draft programme (createOffer: always a draft, version 1).
export default async function NewDiscountPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canWriteOffers(session.user.role, grants)) return <Forbidden locale={locale} />;
  const refs = await loadOfferRefs(session.at);
  return (
    <div className="space-y-4" data-testid="discount-new-page">
      <PageHeading title={t(locale, "disc.new.title")} actions={<Link href="/admin/discounts" className="text-body-strong text-accent hover:underline">{t(locale, "disc.back")}</Link>} />
      {refs.failed ? <Banner tone="warning" role="alert" testId="disc-refs-failed">{t(locale, "error.ref_load")}</Banner> : null}
      <OfferFormView mode="create" initial={emptyForm()} skus={refs.skus} productNodes={refs.productNodes} nodes={refs.nodes} backHref="/admin/discounts" />
    </div>
  );
}
