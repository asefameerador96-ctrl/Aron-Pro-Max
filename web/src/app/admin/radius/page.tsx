import { Forbidden } from "@/components/forbidden";
import { RadiusProposal } from "@/components/admin/radius-proposal";
import { hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// F-TSO-025: a TSO proposes the geofence radius of its own territory (D24-59). The territories are the ones in the TOKEN's scope;
// the BFF refuses any other id, and the API decides applied, scheduled or waiting for approval.
export default async function RadiusPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ["TSO"])) return <Forbidden locale={locale} />;
  const territories = (session.scope?.nodes ?? []).filter((n) => n.type === "territory").map((n) => ({ id: n.id, label: [n.code, n.name].filter(Boolean).join(" · ") || String(n.id) }));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.radius")}</h1>
      {territories.length === 0 ? <p className="text-slate-600">{t(locale, "radius.none")}</p> : <RadiusProposal territories={territories} />}
    </div>
  );
}
