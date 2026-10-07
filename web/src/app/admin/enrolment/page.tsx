import { EnrolmentView } from "@/components/admin/config/enrolment-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { latestVersion } from "@/lib/admin/config-load";
import type { DevicePage, EnrolmentTokenPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function EnrolmentPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const [tokens, devices, ver] = await Promise.all([apiGet<EnrolmentTokenPage>("/v1/admin/enrolment-tokens", session.at, { active_only: "true", limit: 50 }), apiGet<DevicePage>("/v1/admin/devices", session.at, { limit: 50 }), latestVersion(session.at)]);
  if (!tokens.ok) return onApiFailure(tokens.status, tokens.problem, locale);
  return <EnrolmentView locale={locale} tokens={tokens.data.items} devices={devices.ok ? devices.data.items : []} currentPolicy={ver.ok && ver.data ? ver.data.version : null} canWrite={canOp("enrolment.create", session.user.role)} />;
}
