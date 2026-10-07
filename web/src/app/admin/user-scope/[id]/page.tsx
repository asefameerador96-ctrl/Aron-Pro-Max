import { notFound } from "next/navigation";
import { Forbidden } from "@/components/forbidden";
import { ScopeEditor, type NodeOption, type ScopeNodeRow } from "@/components/admin/scope-editor";
import { loadRefOptions } from "@/components/admin/crud/server";
import { apiClient, outcome } from "@/lib/api/client";
import { ALLOWED_SCOPE_TYPES } from "@/lib/admin/scope-rules";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";

const LEVELS = ["wing", "division", "territory", "zone"] as const;
const ROLE_KEY = (r: string) => `role.${r}` as MessageKey;

// User scope assignment (F-ADM-008): replace a user's supervisory nodes from a date; role-node consistency is enforced here
// (the options) and again in the BFF (master-op-rules). The acting scope with an end date is not expressible in the contract's
// write schema: docs/requests/web-admin-acting-scope.md.
export default async function UserScopePage({ params }: { params: Promise<{ id: string }> }) {
  const [{ id }, session, locale] = await Promise.all([params, requireSession(), getLocale()]);
  if (!/^[0-9]{1,15}$/.test(id)) notFound();
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const client = apiClient(session.at);
  const [user, scope] = await Promise.all([outcome(client.GET("/v1/admin/users/{id}", { params: { path: { id: Number(id) } } })), outcome(client.GET("/v1/admin/users/{id}/scope", { params: { path: { id: Number(id) } } }))]);
  if (!user.ok) {
    if (user.status === 404) notFound();
    return <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{problemMessage(locale, user.problem.code)}</p>;
  }
  if (!scope.ok) return <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{problemMessage(locale, scope.problem.code)}</p>;

  const allowed = [...ALLOWED_SCOPE_TYPES[user.data.role]];
  const options: Record<string, NodeOption[]> = {};
  await Promise.all(LEVELS.filter((l) => allowed.includes(l)).map(async (l) => void (options[l] = await loadRefOptions({ path: "/v1/admin/geo/{level}", params: { level: l }, label: ["code", "name"] }, session.at))));
  const label = (type: string, nodeId: number) => (type === "national" ? t(locale, "scope.national") : (options[type]?.find((o) => o.value === String(nodeId))?.label ?? String(nodeId)));
  const current: ScopeNodeRow[] = scope.data.nodes.filter((n) => !n.valid_to || n.valid_to >= businessDate()).map((n) => ({ type: n.node_type, id: n.node_id, label: label(n.node_type, n.node_id), validTo: n.valid_to ?? null }));
  // ADMIN cannot write ADMIN-role users (docs/24 s8.5).
  const canWrite = ["ADMIN", "SUPERADMIN"].includes(session.user.role) && (session.user.role === "SUPERADMIN" || !["ADMIN", "SUPERADMIN"].includes(user.data.role));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "scope.assign_title")}</h1>
      <p className="text-slate-700" data-testid="scope-user">
        {user.data.username} · {user.data.full_name} · {t(locale, ROLE_KEY(user.data.role))}
      </p>
      <ScopeEditor userId={user.data.id} allowedTypes={allowed} current={current} options={options} minDate={businessDate()} canWrite={canWrite} />
    </div>
  );
}
