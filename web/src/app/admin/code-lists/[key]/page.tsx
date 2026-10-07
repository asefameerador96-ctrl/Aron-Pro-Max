import { notFound } from "next/navigation";
import { CodeListEditor, type EditorItem } from "@/components/admin/codelist-editor";
import { CODELIST_WRITE_ROLES } from "@/components/admin/crud/codelists-server";
import { Forbidden } from "@/components/forbidden";
import { apiClient, outcome } from "@/lib/api/client";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { problemMessage, t } from "@/lib/i18n";
import { codeListByKey } from "../../_codelists/registry";

export default async function CodeListPage({ params }: { params: Promise<{ key: string }> }) {
  const [{ key }, session, locale] = await Promise.all([params, requireSession(), getLocale()]);
  const meta = codeListByKey(key);
  if (!meta) notFound();
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await outcome(apiClient(session.at).GET("/v1/admin/code-lists"));
  if (!r.ok) {
    return (
      <p role="alert" className="rounded border border-red-200 bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-[var(--danger)]">
        {problemMessage(locale, r.problem.code)}
      </p>
    );
  }
  const list = r.data.lists.find((l) => l.list_key === key);
  const items: EditorItem[] = [...(list?.items ?? [])]
    .sort((a, b) => a.sort - b.sort)
    .map((i) => ({
      code: i.code,
      label_en: i.label_en,
      label_bn: i.label_bn ?? "",
      sort: String(i.sort),
      valid_from: i.valid_from ?? "",
      valid_to: i.valid_to ?? "",
      attrs: Object.fromEntries((meta.attrs ?? []).map((a) => [a.key, i.attrs?.[a.key] == null ? "" : String(i.attrs[a.key])])),
      rawAttrs: Object.fromEntries(Object.entries(i.attrs ?? {}).filter(([k]) => !(meta.attrs ?? []).some((a) => a.key === k))),
      saved: true,
    }));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, meta.labelKey)}</h1>
      <CodeListEditor listKey={key} items={items} attrs={(meta.attrs ?? []).map((a) => ({ key: a.key, label: t(locale, a.labelKey), options: a.options ? [...a.options] : undefined }))} canWrite={(CODELIST_WRITE_ROLES as readonly string[]).includes(session.user.role)} />
    </div>
  );
}
