// Server-side reference data of the discount screens (portal-discount-web): the SKUs with their pack units, product nodes and
// geography/route nodes for the pickers and for the labels of ids. A failed load never blocks reading a programme: the ids show.
import { loadAllRows, loadRefOptions } from "@/components/admin/crud/server";
import type { Option, SkuOption } from "@/components/admin/offer-form";
import type { OfferSkuUnit, SkuUnit } from "./offers";
import { PACK_LABEL, BASE_UNIT_OF, type SkuCategory } from "./sku-pack";
import { SCOPE_NODE_TYPES } from "./offers";

type NodeType = (typeof SCOPE_NODE_TYPES)[number];

/** The server's field unit (contract 1.12.1, OfferSkuUnit): `pack` for cigarettes, bidi and lighters, `dozen` for matches, the SKU's own
 *  base unit for any other category. The display word (packet / box) is derived in lib/admin/offers.ts displayUnit. */
function unitOfSku(row: Record<string, unknown>): SkuUnit {
  const cat = String(row.category_code) as SkuCategory;
  const known = cat in PACK_LABEL;
  const base = (known ? BASE_UNIT_OF[cat] : String(row.base_unit)) as OfferSkuUnit["base_unit"];
  return { sku_id: Number(row.id), base_unit: base, pack_factor: Number(row.base_per_pack ?? 1), field_unit: !known ? base : cat === "match" ? "dozen" : "pack", category_code: cat };
}

export interface OfferRefs {
  skus: SkuOption[];
  productNodes: Record<"category" | "brand" | "variant", Option[]>;
  nodes: Record<NodeType, Option[]>;
  failed: boolean;
}

export async function loadOfferRefs(token: string, want: { skus?: boolean; productLevels?: readonly string[]; nodeTypes?: readonly string[] } = { skus: true, productLevels: ["category", "brand", "variant"], nodeTypes: SCOPE_NODE_TYPES }): Promise<OfferRefs> {
  let failed = false;
  const skuRows = want.skus === false ? { rows: [], failed: false } : await loadAllRows("/v1/admin/skus", token, { status: "active" });
  failed ||= skuRows.failed;
  const skus: SkuOption[] = skuRows.rows.map((r) => ({ id: Number(r.id), label: `${String(r.code)} · ${String(r.name)}`, unit: unitOfSku(r) }));
  const productNodes = { category: [], brand: [], variant: [] } as OfferRefs["productNodes"];
  for (const level of ["category", "brand", "variant"] as const) {
    if (want.productLevels && !want.productLevels.includes(level)) continue;
    const rows = await loadAllRows(`/v1/admin/product-nodes/${level}`, token);
    failed ||= rows.failed;
    productNodes[level] = rows.rows.map((r) => ({ value: String(r.id), label: String(r.name ?? r.code ?? r.id) }));
  }
  const nodes = { wing: [], division: [], territory: [], zone: [], route: [] } as OfferRefs["nodes"];
  for (const type of SCOPE_NODE_TYPES) {
    if (want.nodeTypes && !want.nodeTypes.includes(type)) continue;
    const ref = type === "route" ? ({ path: "/v1/admin/routes", label: ["code", "name"] } as const) : ({ path: "/v1/admin/geo/{level}", params: { level: type }, label: ["code", "name"] } as const);
    try {
      nodes[type] = await loadRefOptions(ref, token);
    } catch {
      failed = true;
    }
  }
  return { skus, productNodes, nodes, failed };
}
