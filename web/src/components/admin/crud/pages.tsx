// Server-rendered pages of the CRUD generator. The routes under src/app/admin/[entity] are one-line wrappers.
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import type { ReactNode } from "react";
import { Forbidden } from "@/components/forbidden";
import { currentPath, requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { formatDateTime, formatNumber, problemMessage, t, type Locale } from "@/lib/i18n";
import type { Problem } from "@/contract/types";
import { AuditHistory } from "../audit-history";
import { DataTable, type Column } from "../kit/data-table";
import { FilterBar, type FilterControl } from "../kit/filter-bar";
import { EntityForm, type FormFieldDef } from "./entity-form";
import { canRead, canWrite, isWritable, type AnyEntity, type AnyField } from "./meta";
import { getRow, history, listRows } from "./server";

type SearchParams = Record<string, string | string[] | undefined>;
const one = (v: string | string[] | undefined, max = 80): string | undefined => (Array.isArray(v) ? v[0] : v)?.slice(0, max) || undefined;
const CURSOR_MAX = 512; // PageCursor maxLength in the contract

/** 401 from the API: rotate the cookies once through the refresh route; a second 401 means sign in again. */
export async function onApiFailure(status: number, problem: Problem, locale: Locale): Promise<ReactNode> {
  if (status === 401) {
    const path = await currentPath();
    redirect(path.includes("r=1") ? `/login?next=${encodeURIComponent(path)}` : `/api/bff/session/refresh?next=${encodeURIComponent(path + (path.includes("?") ? "&" : "?") + "r=1")}`);
  }
  if (status === 403) return <Forbidden locale={locale} />;
  if (status === 404) notFound();
  return (
    <p role="alert" data-testid="api-error" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">
      {problemMessage(locale, problem.code)}
    </p>
  );
}

function optionLabel(locale: Locale, f: { options?: readonly string[]; optionKeys?: AnyField["optionKeys"] }, value: string): string {
  const key = f.optionKeys?.[value];
  return key ? t(locale, key) : value;
}

function cell(locale: Locale, f: AnyField, row: Record<string, unknown>): ReactNode {
  const v = row[f.name];
  if (v === null || v === undefined || v === "") return <span className="text-slate-400">—</span>;
  if (f.kind === "int") return formatNumber(locale, Number(v), { useGrouping: false });
  if (f.kind === "timestamp") return formatDateTime(locale, String(v));
  if (f.kind === "enum") return optionLabel(locale, f, String(v));
  return String(v);
}

function toFormField(locale: Locale, f: AnyField): FormFieldDef {
  return {
    name: f.name,
    label: t(locale, f.labelKey),
    kind: f.kind === "timestamp" ? "text" : f.kind,
    required: Boolean(f.required),
    nullable: Boolean(f.nullable),
    maxLength: f.maxLength,
    options: f.options?.map((o) => ({ value: o, label: optionLabel(locale, f, o) })),
  };
}

export async function EntityListPage({ meta, searchParams }: { meta: AnyEntity; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canRead(meta, session.user.role)) return <Forbidden locale={locale} />;
  const writable = canWrite(meta, session.user.role);

  const query: Record<string, string | undefined> = {};
  for (const f of meta.filters) query[f.param] = one(searchParams[f.param]);
  const cursor = one(searchParams.cursor, CURSOR_MAX);
  const r = await listRows(meta, session.at, query, cursor);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);

  const columns: Column<Record<string, unknown>>[] = meta.fields
    .filter((f) => f.column)
    .map((f) => ({ key: f.name, header: t(locale, f.labelKey), render: (row) => cell(locale, f, row), align: f.kind === "int" ? "right" : "left" }));
  const hint = cursor ? `&c=${encodeURIComponent(cursor)}` : "";
  columns.push({
    key: "_actions",
    header: t(locale, "common.actions"),
    render: (row) => (writable ? <Link className="text-brand-700 underline" href={`/admin/${meta.slug}/${encodeURIComponent(String(row[meta.idField]))}${hint ? `?${hint.slice(1)}` : ""}`}>{t(locale, "common.edit")}</Link> : null),
  });

  const controls: FilterControl[] = meta.filters.map((f) => ({
    param: f.param,
    label: t(locale, f.labelKey),
    kind: f.kind,
    value: query[f.param] ?? "",
    options: f.options?.map((o) => ({ value: o, label: optionLabel(locale, f, o) })),
  }));
  const keep = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) if (v) keep.set(k, v);
  if (r.data.next_cursor) keep.set("cursor", r.data.next_cursor);
  const nextHref = r.data.next_cursor ? `/admin/${meta.slug}?${keep.toString()}` : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, meta.labelKey)}</h1>
        {writable ? (
          <Link href={`/admin/${meta.slug}/new`} data-testid="create-link" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
            {t(locale, "admin.new", { entity: t(locale, meta.singularKey) })}
          </Link>
        ) : (
          <p className="text-sm text-slate-600">{t(locale, "admin.read_only")}</p>
        )}
      </div>
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref={`/admin/${meta.slug}`} />
      <DataTable columns={columns} rows={r.data.items} rowKey={(row) => String(row[meta.idField])} empty={t(locale, "common.empty")} caption={t(locale, meta.labelKey)} />
      {nextHref ? (
        <p>
          <Link href={nextHref} data-testid="next-page" className="text-brand-700 underline">
            {t(locale, "common.next")} →
          </Link>
        </p>
      ) : null}
    </div>
  );
}

export async function EntityCreatePage({ meta }: { meta: AnyEntity }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canWrite(meta, session.user.role)) return <Forbidden locale={locale} />;
  const fields = meta.fields.filter((f) => isWritable(f, "create")).map((f) => toFormField(locale, f));
  const initial = Object.fromEntries(fields.map((f) => [f.name, f.kind === "enum" && f.required ? (f.options?.[0]?.value ?? "") : ""]));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "admin.new", { entity: t(locale, meta.singularKey) })}</h1>
      <EntityForm mode="create" slug={meta.slug} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} />
    </div>
  );
}

export async function EntityEditPage({ meta, id, searchParams }: { meta: AnyEntity; id: string; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canRead(meta, session.user.role)) return <Forbidden locale={locale} />;
  if (!canWrite(meta, session.user.role)) return <Forbidden locale={locale} />;
  const row = await getRow(meta, session.at, id, one(searchParams.c, CURSOR_MAX));
  if (!row.ok) return onApiFailure(row.status, row.problem, locale);
  if (!row.data) notFound();
  const hist = await history(meta, session.at, id);

  const fields = meta.fields.filter((f) => isWritable(f, "update")).map((f) => toFormField(locale, f));
  const initial = Object.fromEntries(fields.map((f) => [f.name, row.data?.[f.name] === null || row.data?.[f.name] === undefined ? "" : String(row.data[f.name])]));
  const version = Number(row.data.version);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "admin.edit", { entity: t(locale, meta.singularKey) })}</h1>
      <EntityForm mode="update" slug={meta.slug} id={id} version={version} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} />
      <AuditHistory entries={hist.ok ? hist.data.items : []} locale={locale} />
    </div>
  );
}
