import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { OutletChangeForm, type OutletFacts } from "@/components/admin/outlet-change-form";
import { loadAllRows, loadRefOptionsChecked } from "@/components/admin/crud/server";
import { onApiFailure } from "@/components/admin/crud/pages";
import { rawRequest } from "@/lib/api/raw";
import { hasRole, OUTLET_REQUEST_RAISE_ROLES } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

type SearchParams = Record<string, string | string[] | undefined>;
const one = (v: string | string[] | undefined, max = 80): string => (Array.isArray(v) ? v[0] : v)?.slice(0, max) ?? "";
const str = (v: unknown): string => (v === null || v === undefined ? "" : String(v));

// F-WEB-003: a TSO or DMO proposes a change (info, location, cluster, closure) to an outlet in its reach; the API creates a pending
// request (POST /v1/outlet-requests) that an AMO verifies and a DMO approves. Which outlets are searchable is the server's reach.
export default async function OutletChangePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, OUTLET_REQUEST_RAISE_ROLES)) return <Forbidden locale={locale} />;
  const q = one(sp.q).trim();
  const pick = /^[0-9]{1,15}$/.test(one(sp.outlet, 40)) ? one(sp.outlet, 40) : "";

  let rows: Record<string, unknown>[] = [];
  if (Array.from(q).length >= 2) {
    const r = await rawRequest<{ items: Record<string, unknown>[] }>({ method: "GET", path: "/v1/outlets", token: session.at, query: { q, limit: 25 } });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    rows = r.data.items;
  }
  const chosen = pick ? rows.find((o) => String(o.id) === pick) : undefined;

  let form = null;
  let failedLoads = false;
  if (chosen) {
    const zone = str(chosen.zone_id);
    const [codeLists, clusters] = await Promise.all([
      Promise.all([
        loadRefOptionsChecked({ path: "/v1/admin/code-lists", codeList: "sub_channel", label: ["label_en", "label_bn"] }, session.at),
        loadRefOptionsChecked({ path: "/v1/admin/code-lists", codeList: "outlet_close_reason", value: "code", label: ["label_en", "label_bn"] }, session.at),
      ]),
      /^[0-9]{1,15}$/.test(zone) ? loadAllRows("/v1/admin/clusters", session.at, { zone_id: zone }) : Promise.resolve({ rows: [], failed: false }),
    ]);
    failedLoads = codeLists[0].failed || codeLists[1].failed || clusters.failed;
    const facts: OutletFacts = {
      id: Number(chosen.id),
      code: str(chosen.code),
      name: str(chosen.name),
      name_bn: str(chosen.name_bn),
      owner_name: str(chosen.owner_name),
      contact_number: "",
      address: str(chosen.address),
      cluster_id: str(chosen.cluster_id),
      sub_channel_id: str(chosen.sub_channel_id),
      lat: str(chosen.lat),
      lng: str(chosen.lng),
    };
    form = (
      <OutletChangeForm
        key={facts.id}
        outlet={facts}
        clusters={clusters.rows.map((c) => ({ value: str(c.id), label: [str(c.name), str(c.cluster_type)].filter(Boolean).join(" · ") }))}
        subChannels={codeLists[0].options}
        closeReasons={codeLists[1].options}
      />
    );
  }

  const loadFailed = chosen ? failedLoads : false;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.outlet_change")}</h1>
      <p className="text-sm text-slate-700">{t(locale, "och.intro")}</p>
      <form method="get" className="flex flex-wrap items-end gap-3" data-testid="och-search" role="search">
        <label className="text-sm">
          <span className="mb-1 block">{t(locale, "och.search")}</span>
          <input name="q" defaultValue={q} minLength={2} maxLength={80} className="min-h-11 w-72 rounded border border-slate-300 px-3 py-2 text-sm" />
        </label>
        <button type="submit" className="rounded border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100">{t(locale, "common.filter")}</button>
      </form>
      {q && Array.from(q).length < 2 ? <p className="text-sm text-slate-600">{t(locale, "och.search_short")}</p> : null}
      {Array.from(q).length >= 2 && rows.length === 0 ? <p data-testid="och-none" className="text-slate-600">{t(locale, "common.empty")}</p> : null}
      {rows.length > 0 ? (
        <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white" data-testid="och-results">
          {rows.map((o) => (
            <li key={String(o.id)} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
              <span className="break-words">{`${str(o.code)} · ${str(o.name)} · ${str(o.owner_name)}`}</span>
              <Link href={`/admin/outlet-change?q=${encodeURIComponent(q)}&outlet=${encodeURIComponent(String(o.id))}`} data-testid={`och-pick-${String(o.id)}`} className="text-brand-700 underline" aria-current={String(o.id) === pick ? "true" : undefined}>
                {t(locale, "och.pick")}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
      {pick && !chosen && rows.length > 0 ? <p role="alert" className="text-red-700">{t(locale, "och.not_found")}</p> : null}
      {loadFailed ? <p role="alert" data-testid="api-error" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
      {form}
    </div>
  );
}
