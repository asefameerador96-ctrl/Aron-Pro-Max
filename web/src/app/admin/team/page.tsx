import { Forbidden } from "@/components/forbidden";
import { TeamCredentials, type TeamUser } from "@/components/admin/team-credentials";
import { loadAllRows } from "@/components/admin/crud/server";
import { hasRole, TEAM_CREDENTIAL_ROLES } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Team credentials (F-TSO-023): a TSO resets a password (temporary, 24 hours, forces a change) or unlocks an SR or AMO of its own
// zones. Which users are listed is the server's reach; the page never sends zone ids.
export default async function TeamPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, TEAM_CREDENTIAL_ROLES)) return <Forbidden locale={locale} />;
  const [srs, amos] = await Promise.all([loadAllRows("/v1/admin/users", session.at, { role: "SR" }), loadAllRows("/v1/admin/users", session.at, { role: "AMO" })]);
  const users = [...srs.rows, ...amos.rows] as unknown as TeamUser[];
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.team")}</h1>
      {srs.failed || amos.failed ? <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
      <TeamCredentials users={users} />
    </div>
  );
}
