// Server-rendered pages of the CRUD generator. The routes under src/app/admin/[entity] are one-line wrappers.
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import type { ReactNode } from "react";
import { Forbidden } from "@/components/forbidden";
import { currentPath, requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { formatBusinessDate, formatDateTime, formatNumber, problemMessage, t, type Locale } from "@/lib/i18n";
import type { Problem } from "@/contract/types";
import { AuditHistory } from "../audit-history";
import { DataTable, type Column } from "../kit/data-table";
import { FilterBar, type FilterControl } from "../kit/filter-bar";
import { EntityForm, type FormFieldDef } from "./entity-form";
import { canRead, canWrite, entityCanEdit, isWritable, type ActionMeta, type AnyEntity, type AnyField } from "./meta";
import { getRow, history, listRows, loadRefLabel, loadRefOptionsChecked, sanitizeFilters, type RefOption } from "./server";

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

type RefOptions = Record<string, RefOption[]>;

interface RefLoad {
  refs: RefOptions;
  /** A referenced list could not be loaded: the form would otherwise show an empty required select with no explanation. */
  failed: boolean;
}

/**
 * Options of the `ref` fields and filters an entity page needs. One call per distinct referenced table (a table used by a
 * column and a filter is fetched once); `scope: "list"` loads only what the list shows (column fields and filters),
 * "form" loads the fields of the form.
 */
async function loadAllRefs(meta: AnyEntity, token: string, scope: "list" | "create" | "update" | { fields: readonly AnyField[] }): Promise<RefLoad> {
  const wanted: { slot: string; ref: NonNullable<AnyField["ref"]> }[] = [];
  const fields: readonly AnyField[] =
    typeof scope === "object" ? scope.fields
    : scope === "list" ? meta.fields.filter((f) => f.column && f.refLabels !== false)
    : meta.fields.filter((f) => isWritable(f, scope));
  for (const f of fields) if (f.kind === "ref" && f.ref) wanted.push({ slot: f.name, ref: f.ref });
  if (scope === "list") for (const f of meta.filters) if (f.kind === "ref" && f.ref) wanted.push({ slot: `filter:${f.param}`, ref: f.ref });
  const byTable = new Map<string, Promise<{ options: RefOption[]; failed: boolean }>>();
  const tableKey = (r: NonNullable<AnyField["ref"]>) => JSON.stringify([r.path, r.params, r.value, r.label]);
  for (const w of wanted) if (!byTable.has(tableKey(w.ref))) byTable.set(tableKey(w.ref), loadRefOptionsChecked(w.ref, token));
  const refs: RefOptions = {};
  let failed = false;
  for (const w of wanted) {
    const r = await byTable.get(tableKey(w.ref))!;
    refs[w.slot] = r.options;
    failed ||= r.failed;
  }
  return { refs, failed };
}

function pathValue(row: Record<string, unknown>, path: string): unknown {
  return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), row);
}

function shown(locale: Locale, v: unknown): ReactNode {
  if (v === null || v === undefined || v === "") return null;
  if (typeof v === "boolean") return t(locale, v ? "common.yes" : "common.no");
  if (Array.isArray(v)) return v.length === 0 ? null : v.map(String).join(", ");
  if (typeof v === "number") return formatNumber(locale, v, { useGrouping: false, maximumFractionDigits: 7 });
  return String(v);
}

/** Read-only facts of the loaded row: members the form cannot edit and the proposal a decision is about. */
function Details({ items }: { items: { label: string; value: ReactNode }[] }) {
  if (items.length === 0) return null;
  return (
    <dl className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-4 text-sm md:grid-cols-3" data-testid="row-details">
      {items.map((i) => (
        <div key={i.label}>
          <dt className="text-slate-500">{i.label}</dt>
          <dd>{i.value}</dd>
        </div>
      ))}
    </dl>
  );
}

function RefFailure({ locale }: { locale: Locale }) {
  return (
    <p role="alert" data-testid="ref-error" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">
      {t(locale, "error.ref_load")}
    </p>
  );
}

function cell(locale: Locale, f: AnyField, row: Record<string, unknown>, refs: RefOptions = {}): ReactNode {
  const v = row[f.name];
  if (v === null || v === undefined || v === "") return <span className="text-slate-400">—</span>;
  if (f.kind === "date") return formatBusinessDate(locale, String(v));
  if (f.kind === "mask") return f.maskBits?.filter((_, bit) => (Number(v) & (1 << bit)) !== 0).map((k) => t(locale, k)).join(" ") ?? String(v);
  if (f.kind === "bool") return t(locale, v ? "common.yes" : "common.no");
  if (f.kind === "ref") return refs[f.name]?.find((o) => o.value === String(v))?.label ?? formatNumber(locale, Number(v), { useGrouping: false });
  if (f.kind === "int") return formatNumber(locale, Number(v), { useGrouping: false });
  if (f.kind === "timestamp") return formatDateTime(locale, String(v));
  if (f.kind === "enum") return optionLabel(locale, f, String(v));
  return String(v);
}

/** Options an actor may not choose (docs/24 s8.5 restricted values) are not offered at all. */
function hidden(meta: AnyEntity | undefined, role: string | undefined, field: string, value: string): boolean {
  return (meta?.restrictedValues ?? []).some((r) => r.field === field && r.values.includes(value) && !(r.unlessRoles as readonly string[]).includes(role ?? ""));
}

function toFormField(locale: Locale, f: AnyField, refs: RefOptions = {}, meta?: AnyEntity, role?: string): FormFieldDef {
  const kind: FormFieldDef["kind"] = f.kind === "timestamp" ? "text" : f.kind === "ref" || f.kind === "bool" ? "enum" : f.kind;
  const maskBits = f.kind === "mask" ? f.maskBits?.map((k) => t(locale, k)) : undefined;
  const options =
    f.kind === "ref" ? refs[f.name]
    : f.kind === "bool" ? [{ value: "true", label: t(locale, "common.yes") }, { value: "false", label: t(locale, "common.no") }]
    : f.options?.filter((o) => !hidden(meta, role, f.name, o)).map((o) => ({ value: o, label: optionLabel(locale, f, o) }));
  return { name: f.name, label: t(locale, f.labelKey), section: f.section ? t(locale, f.section) : undefined, kind, required: Boolean(f.required), nullable: Boolean(f.nullable), maxLength: f.maxLength, options, maskBits };
}

export async function EntityListPage({ meta, searchParams }: { meta: AnyEntity; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canRead(meta, session.user.role)) return <Forbidden locale={locale} />;
  const writable = canWrite(meta, session.user.role);

  const rawQuery: Record<string, string | undefined> = {};
  for (const f of meta.filters) rawQuery[f.param] = one(searchParams[f.param]);
  const query = sanitizeFilters(meta, rawQuery);
  const cursor = one(searchParams.cursor, CURSOR_MAX);
  const [r, { refs, failed }] = await Promise.all([listRows(meta, session.at, query, cursor), loadAllRefs(meta, session.at, "list")]);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);

  const columns: Column<Record<string, unknown>>[] = meta.fields
    .filter((f) => f.column)
    .map((f) => ({ key: f.name, header: t(locale, f.labelKey), render: (row) => cell(locale, f, row, refs), align: f.kind === "int" ? "right" : "left" }));
  const hint = cursor ? `&c=${encodeURIComponent(cursor)}` : "";
  const editable = entityCanEdit(meta);
  const rowActions = (meta.actions ?? []).filter((a) => (a.writeRoles ?? meta.writeRoles).includes(session.user.role));
  const rowLinks = (meta.links ?? []).filter((l) => (l.roles ?? meta.writeRoles).includes(session.user.role));
  if (editable || rowActions.length > 0 || rowLinks.length > 0) {
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
            {rowLinks.map((l) => (
              <Link key={l.key} className="text-brand-700 underline" data-testid={`link-${l.key}`} href={l.href(String(row[meta.idField]))}>
                {t(locale, l.labelKey)}
              </Link>
            ))}
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
      {failed ? <RefFailure locale={locale} /> : null}
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
  const { refs, failed } = await loadAllRefs(meta, session.at, "create");
  const fields = meta.fields.filter((f) => isWritable(f, "create")).map((f) => toFormField(locale, f, refs, meta, session.user.role));
  const initial = Object.fromEntries(fields.map((f) => [f.name, f.kind === "enum" && f.required ? (f.options?.[0]?.value ?? "") : ""]));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "admin.new", { entity: t(locale, meta.singularKey) })}</h1>
      {failed ? <RefFailure locale={locale} /> : null}
      <EntityForm mode="create" slug={meta.slug} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} reasonStored={meta.reasonOnCreate !== null} />
    </div>
  );
}

export async function EntityEditPage({ meta, id, searchParams }: { meta: AnyEntity; id: string; searchParams: SearchParams }) {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!canRead(meta, session.user.role)) return <Forbidden locale={locale} />;
  if (!entityCanEdit(meta)) notFound(); // an entity without an edit (assignments, holidays) has no edit URL
  if (!canWrite(meta, session.user.role)) return <Forbidden locale={locale} />;
  const row = await getRow(meta, session.at, id, one(searchParams.c, CURSOR_MAX));
  if (!row.ok) return onApiFailure(row.status, row.problem, locale);
  if (!row.data) notFound();
  const [hist, { refs, failed }] = await Promise.all([history(meta, session.at, id), loadAllRefs(meta, session.at, "update")]);

  const fields = meta.fields.filter((f) => isWritable(f, "update")).map((f) => toFormField(locale, f, refs, meta, session.user.role));
  const initial = Object.fromEntries(fields.map((f) => [f.name, row.data?.[f.name] === null || row.data?.[f.name] === undefined ? "" : String(row.data[f.name])]));
  const version = Number(row.data.version);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "admin.edit", { entity: t(locale, meta.singularKey) })}</h1>
      {failed ? <RefFailure locale={locale} /> : null}
      <Details items={meta.fields.filter((f) => f.mode === "readonly" && !f.column).map((f) => ({ label: t(locale, f.labelKey), value: cell(locale, f, row.data as Record<string, unknown>, refs) }))} />
      <EntityForm mode="update" slug={meta.slug} id={id} version={version} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} reasonStored={meta.reasonOnUpdate !== null} />
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
  const { refs } = await loadAllRefs(meta, session.at, { fields: action.fields.map(actionField) });
  const fields = action.fields.map((f) => toFormField(locale, actionField(f), refs));
  const initial = Object.fromEntries(fields.map((f) => [f.name, ""]));
  const summary = meta.fields.filter((f) => f.column).slice(0, 6);
  // Summary labels: one GET per referenced value (never the whole table).
  const summaryRefs: RefOptions = {};
  await Promise.all(summary.filter((f) => f.kind === "ref" && f.ref).map(async (f) => {
    const id = String((row.data as Record<string, unknown>)[f.name] ?? "");
    const label = await loadRefLabel(f.ref!, id, session.at);
    if (label) summaryRefs[f.name] = [{ value: id, label }];
  }));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, action.labelKey)}</h1>
      <dl className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-4 text-sm md:grid-cols-3" data-testid="row-summary">
        {summary.map((f) => (
          <div key={f.name}>
            <dt className="text-slate-500">{t(locale, f.labelKey)}</dt>
            <dd>{cell(locale, f, row.data as Record<string, unknown>, summaryRefs)}</dd>
          </div>
        ))}
      </dl>
      <Details items={(meta.detailFields ?? []).flatMap((d) => { const v = shown(locale, pathValue(row.data as Record<string, unknown>, d.path)); return v === null ? [] : [{ label: t(locale, d.labelKey), value: v }]; })} />
      <EntityForm mode="action" slug={meta.slug} action={action.key} submitLabel={t(locale, action.labelKey)} id={id} version={action.ifMatch ? Number(row.data.version) : undefined} fields={fields} initial={initial} listHref={`/admin/${meta.slug}`} />
    </div>
  );
}
