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
import { canRead, canWrite, entityCanEdit, isWritable, type ActionMeta, type AnyEntity, type AnyField } from "./meta";
import { getRow, history, listRows, loadRefOptions, type RefOption } from "./server";

type SearchParams = Record<string, string | string[] | undefined>;
const one = (v: string | string[] | undefined, max = 80): string | undefined => (Array.isArray(v) ? v[0] : v)?.slice(0, max) || undefined;
const CURSOR_MAX = 512; // PageCursor maxLength in the contract

/** 401 from the API: rotate the cookies once through the refresh route; a second 401 means sign in again. */
async function onApiFailure(status: number, problem: Problem, locale: Locale): Promise<ReactNode> {
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

type RefOptions = Record<string, RefOption[]>;

/** Options of every `ref` field and filter of an entity, keyed by field name or filter param. */
async function loadAllRefs(meta: AnyEntity, token: string, onlyFields = false): Promise<RefOptions> {
  const jobs: Promise<[string, RefOption[]]>[] = [];
  for (const f of meta.fields) if (f.kind === "ref" && f.ref) jobs.push(loadRefOptions(f.ref, token).then((o) => [f.name, o]));
  if (!onlyFields) for (const f of meta.filters) if (f.kind === "ref" && f.ref) jobs.push(loadRefOptions(f.ref, token).then((o) => [`filter:${f.param}`, o]));
  return Object.fromEntries(await Promise.all(jobs));
}

function cell(locale: Locale, f: AnyField, row: Record<string, unknown>, refs: RefOptions = {}): ReactNode {
  const v = row[f.name];
  if (v === null || v === undefined || v === "") return <span className="text-slate-400">—</span>;
  if (f.kind === "mask") return f.maskBits?.filter((_, bit) => (Number(v) & (1 << bit)) !== 0).map((k) => t(locale, k)).join(" ") ?? String(v);
  if (f.kind === "bool") return t(locale, v ? "common.yes" : "common.no");
  if (f.kind === "ref") return refs[f.name]?.find((o) => o.value === String(v))?.label ?? formatNumber(locale, Number(v), { useGrouping: false });
  if (f.kind === "int") return formatNumber(locale, Number(v), { useGrouping: false });
  if (f.kind === "timestamp") return formatDateTime(locale, String(v));
  if (f.kind === "enum") return optionLabel(locale, f, String(v));
  return String(v);
}

function toFormField(locale: Locale, f: AnyField, refs: RefOptions = {}): FormFieldDef {
  const kind: FormFieldDef["kind"] = f.kind === "timestamp" ? "text" : f.kind === "ref" || f.kind === "bool" ? "enum" : f.kind;
  const maskBits = f.kind === "mask" ? f.maskBits?.map((k) => t(locale, k)) : undefined;
  const options =
    f.kind === "ref" ? refs[f.name]
    : f.kind === "bool" ? [{ value: "true", label: t(locale, "common.yes") }, { value: "false", label: t(locale, "common.no") }]
    : f.options?.map((o) => ({ value: o, label: optionLabel(locale, f, o) }));
  return { name: f.name, label: t(locale, f.labelKey), kind, required: Boolean(f.required), nullable: Boolean(f.nullable), maxLength: f.maxLength, options, maskBits };
}

export async function EntityListPage({ meta, searchParams }: { meta: AnyEntity; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canRead(meta, session.user.role)) return <Forbidden locale={locale} />;
  const writable = canWrite(meta, session.user.role);

  const query: Record<string, string | undefined> = {};
  for (const f of meta.filters) query[f.param] = one(searchParams[f.param]);
  const cursor = one(searchParams.cursor, CURSOR_MAX);
  const [r, refs] = await Promise.all([listRows(meta, session.at, query, cursor), loadAllRefs(meta, session.at)]);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);

  const columns: Column<Record<string, unknown>>[] = meta.fields
    .filter((f) => f.column)
    .map((f) => ({ key: f.name, header: t(locale, f.labelKey), render: (row) => cell(locale, f, row, refs), align: f.kind === "int" ? "right" : "left" }));
  const hint = cursor ? `&c=${encodeURIComponent(cursor)}` : "";
  const editable = entityCanEdit(meta);
  const rowActions = (meta.actions ?? []).filter((a) => (a.writeRoles ?? meta.writeRoles).includes(session.user.role));
  if (editable || rowActions.length > 0) {
    columns.push({
      key: "_actions",
      header: t(locale, "common.actions"),
      render: (row) => {
        const id = encodeURIComponent(String(row[meta.idField]));
        return (
          <span className="flex flex-wrap gap-3">
            {writable && editable ? (
              <Link className="text-brand-700 underline" href={`/admin/${meta.slug}/${id}${hint ? `?${hint.slice(1)}` : ""}`}>
                {t(locale, "common.edit")}
              </Link>
            ) : null}
            {rowActions
              .filter((a) => !a.when || a.when(row))
              .map((a) => (
                <Link key={a.key} className="text-brand-700 underline" data-testid={`action-${a.key}`} href={`/admin/${meta.slug}/${id}/${a.key}${hint ? `?${hint.slice(1)}` : ""}`}>
                  {t(locale, a.labelKey)}
                </Link>
              ))}
          </span>
        );
      },
    });
  }

  const controls: FilterControl[] = meta.filters.map((f) => ({
    param: f.param,
    label: t(locale, f.labelKey),
    kind: f.kind === "ref" ? "enum" : f.kind,
    value: query[f.param] ?? "",
    options: f.kind === "ref" ? refs[`filter:${f.param}`] : f.options?.map((o) => ({ value: o, label: optionLabel(locale, f, o) })),
  }));
  const keep = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) if (v) keep.set(k, v);
  if (r.data.next_cursor) keep.set("cursor", r.data.next_cursor);
  const nextHref = r.data.next_cursor ? `/admin/${meta.slug}?${keep.toString()}` : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, meta.labelKey)}</h1>
        {writable && meta.canCreate !== false ? (
          <Link href={`/admin/${meta.slug}/new`} data-testid="create-link" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
            {t(locale, "admin.new", { entity: t(locale, meta.singularKey) })}
          </Link>
        ) : !writable ? (
          <p className="text-sm text-slate-600">{t(locale, "admin.read_only")}</p>
        ) : null}
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
  if (!canWrite(meta, session.user.role) || meta.canCreate === false) return <Forbidden locale={locale} />;
  const refs = await loadAllRefs(meta, session.at, true);
  const fields = meta.fields.filter((f) => isWritable(f, "create")).map((f) => toFormField(locale, f, refs));
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
  if (!canWrite(meta, session.user.role) || !entityCanEdit(meta)) return <Forbidden locale={locale} />;
  const row = await getRow(meta, session.at, id, one(searchParams.c, CURSOR_MAX));
  if (!row.ok) return onApiFailure(row.status, row.problem, locale);
  if (!row.data) notFound();
  const [hist, refs] = await Promise.all([history(meta, session.at, id), loadAllRefs(meta, session.at, true)]);

  const fields = meta.fields.filter((f) => isWritable(f, "update")).map((f) => toFormField(locale, f, refs));
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

function actionField(f: ActionMeta["fields"][number]): AnyField {
  return { ...f, mode: "rw" } as unknown as AnyField;
}

/** Page of a row action: the row's summary, the action's inputs and the mandatory reason. */
export async function EntityActionPage({ meta, id, actionKey, searchParams }: { meta: AnyEntity; id: string; actionKey: string; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  const action = meta.actions?.find((a) => a.key === actionKey);
  if (!action) notFound();
  if (!(action.writeRoles ?? meta.writeRoles).includes(session.user.role)) return <Forbidden locale={locale} />;
  const row = await getRow(meta, session.at, id, one(searchParams.c, CURSOR_MAX));
  if (!row.ok) return onApiFailure(row.status, row.problem, locale);
  if (!row.data) notFound();
  if (action.when && !action.when(row.data)) notFound();
  const refs = await loadAllRefs(meta, session.at, true);
  const fields = action.fields.map((f) => toFormField(locale, actionField(f), refs));
  const initial = Object.fromEntries(fields.map((f) => [f.name, ""]));
  const summary = meta.fields.filter((f) => f.column).slice(0, 6);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, action.labelKey)}</h1>
      <dl className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-4 text-sm md:grid-cols-3" data-testid="row-summary">
        {summary.map((f) => (
          <div key={f.name}>
            <dt className="text-slate-500">{t(locale, f.labelKey)}</dt>
            <dd>{cell(locale, f, row.data as Record<string, unknown>, refs)}</dd>
          </div>
        ))}
      </dl>
      <EntityForm mode="action" slug={meta.slug} action={action.key} submitLabel={t(locale, action.labelKey)} id={id} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} />
    </div>
  );
}
