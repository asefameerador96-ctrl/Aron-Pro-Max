// F-ADM-060 reason-code and list tables (and, fixed to qc_fault_type, F-ADM-023 QC fault-type administration).
import Link from "next/link";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { ATTR_FIELDS } from "@/lib/admin/code-lists";
import type { CodeList, CodeListKey } from "@/lib/admin/types";
import { t, type Locale, type MessageKey } from "@/lib/i18n";
import { CodeListEditor } from "./code-list-editor";

export function CodeListsView({ locale, lists, selected, choices, basePath, today, canWrite, titleKey }: { locale: Locale; lists: CodeList[]; selected: CodeListKey | null; choices: readonly CodeListKey[] | null; basePath: string; today: string; canWrite: boolean; titleKey: "cl.title" | "qc.title" }) {
  const list = lists.find((l) => l.list_key === selected) ?? null;
  const attrs = (selected ? (ATTR_FIELDS[selected] ?? []) : []).map((a) => ({ name: a.name, label: t(locale, `cl.attr.${a.name}` as MessageKey), options: a.options.map((o) => ({ value: o, label: t(locale, `cl.attr.${a.name}.${o}` as MessageKey) })) }));
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, titleKey)} intro={t(locale, titleKey === "qc.title" ? "qc.intro" : "cl.intro")} />
      {choices ? (
        <nav className="flex flex-wrap gap-2" aria-label={t(locale, "cl.title")}>
          {choices.map((k) => (
            <Link key={k} href={`${basePath}?list=${k}`} className={`rounded border px-3 py-1 text-sm ${k === selected ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
              {t(locale, `cl.list.${k}` as MessageKey)}
            </Link>
          ))}
        </nav>
      ) : null}
      {list && selected ? (
        <Card title={t(locale, `cl.list.${selected}` as MessageKey)}>
          <CodeListEditor key={selected} listKey={selected} initial={list.items} attrs={attrs} today={today} canWrite={canWrite} />
        </Card>
      ) : (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "cl.choose")}</p>
      )}
    </div>
  );
}
