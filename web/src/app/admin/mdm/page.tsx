import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { DevicesListView } from "@/components/admin/mdm/devices-view";
import { mdmContext } from "@/components/admin/mdm/shared";
import { MDM_FLAGS, MDM_STATES, type MdmDevicePage, type MdmPolicyPage } from "@/lib/admin/mdm";

const pick = (v: string | undefined, allowed: readonly string[]) => (v && allowed.includes(v) ? v : "");

export default async function MdmDevicesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [{ ctx, forbidden }, sp] = await Promise.all([mdmContext(), searchParams]);
  if (forbidden) return forbidden;
  const q = one(sp.q, 80) ?? "";
  const policy = one(sp.policy_id, 15);
  const filters = {
    status: pick(one(sp.status, 16), MDM_STATES),
    flavour: pick(one(sp.flavour, 8), ["sr", "amo", "tso"]),
    flag: pick(one(sp.flag, 24), MDM_FLAGS),
    policy_id: policy && /^[1-9]\d{0,14}$/.test(policy) ? policy : "",
    q: q.length >= 2 ? q : "",
  };
  const [r, pols] = await Promise.all([apiGet<MdmDevicePage>("/v1/admin/mdm/devices", ctx.session.at, { ...filters, cursor: one(sp.cursor, 512), limit: 50 }), apiGet<MdmPolicyPage>("/v1/admin/mdm/policies", ctx.session.at, { limit: 100 })]);
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  const nextHref = r.data.next_cursor ? `/admin/mdm${qs({ ...filters, cursor: r.data.next_cursor })}` : null;
  return <DevicesListView locale={ctx.locale} rows={r.data.items} policies={pols.ok ? pols.data.items : []} filters={filters} nextHref={nextHref} />;
}
