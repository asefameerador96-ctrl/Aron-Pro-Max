import { Forbidden } from "@/components/forbidden";
import { TutorialManager, type TutorialRow } from "@/components/admin/tutorial-manager";
import { rawRequest } from "@/lib/api/raw";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { problemMessage, t } from "@/lib/i18n";

// Tutorial content (F-ADM-026): videos and the four manuals per role. SUPPORT reads; ADMIN and SUPERADMIN write.
export default async function TutorialsPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await rawRequest<{ items: Record<string, unknown>[] }>({ method: "GET", path: "/v1/admin/tutorials", token: session.at });
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.tutorials")}</h1>
      {r.ok ? (
        <TutorialManager items={r.data.items as unknown as TutorialRow[]} canWrite={hasRole(session.user.role, ["ADMIN", "SUPERADMIN"])} />
      ) : (
        <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">
          {problemMessage(locale, r.problem.code)}
        </p>
      )}
    </div>
  );
}
