import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { LoyaltyFormView } from "@/components/admin/loyalty-form";
import { PageHeading } from "@/components/admin/kit/page";
import { loadGrants } from "@/lib/admin/modules";
import { canWriteLoyalty, emptyForm } from "@/lib/admin/loyalty";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Create a draft programme (createProgramme: always a draft, version 1).
export default async function NewLoyaltyPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canWriteLoyalty(session.user.role, grants)) return <Forbidden locale={locale} />;
  return (
    <div className="space-y-4" data-testid="loyalty-new-page">
      <PageHeading title={t(locale, "loy.new.title")} actions={<Link href="/admin/loyalty" className="text-body-strong text-accent hover:underline">{t(locale, "loy.back")}</Link>} />
      <LoyaltyFormView mode="create" initial={emptyForm()} backHref="/admin/loyalty" />
    </div>
  );
}
