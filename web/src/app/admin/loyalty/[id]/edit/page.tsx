import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { LoyaltyFormView } from "@/components/admin/loyalty-form";
import { PageHeading } from "@/components/admin/kit/page";
import { loadGrants } from "@/lib/admin/modules";
import { canWriteLoyalty, formFromProgramme } from "@/lib/admin/loyalty";
import { loadProgramme } from "@/lib/admin/loyalty-load";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Edit a draft programme: every save appends an immutable version (updateProgramme). Only a draft can be edited (409 otherwise).
export default async function EditLoyaltyPage({ params }: { params: Promise<{ id: string }> }) {
  const [session, locale, { id }] = await Promise.all([requireSession(), getLocale(), params]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canWriteLoyalty(session.user.role, grants)) return <Forbidden locale={locale} />;
  const back = `/admin/loyalty/${id}`;
  const r = /^\d{1,15}$/.test(id) ? await loadProgramme(session.at, Number(id)) : null;
  if (!r || !r.ok || r.data === null) {
    const offline = r !== null && !r.ok && r.offline;
    return <Banner tone={offline ? "offline" : "danger"} role="alert" testId="loyalty-missing">{t(locale, offline ? "loy.offline" : "loy.not_found")} <Link href="/admin/loyalty" className="underline">{t(locale, "loy.back")}</Link></Banner>;
  }
  const p = r.data;
  if (p.status !== "draft") {
    return <Banner tone="warning" role="alert" testId="loyalty-not-draft">{t(locale, "loy.draft_only")} <Link href={back} className="underline">{t(locale, "loy.back_detail")}</Link></Banner>;
  }
  return (
    <div className="space-y-4" data-testid="loyalty-edit-page">
      <PageHeading title={t(locale, "loy.edit.title", { code: p.code })} actions={<Link href={back} className="text-body-strong text-accent hover:underline">{t(locale, "loy.back_detail")}</Link>} />
      <LoyaltyFormView mode="edit" programmeId={p.programme_id} version={p.version} initial={formFromProgramme(p)} backHref={back} />
    </div>
  );
}
