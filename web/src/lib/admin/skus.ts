import { rawRequest } from "@/lib/api/raw";
import type { Sku } from "./types";

/** Every active SKU (pages of 500, at most 8 pages): an entry grid must not silently stop at the first page. */
export async function loadAllSkus(token: string): Promise<Sku[]> {
  const out: Sku[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < 8; i++) {
    const r = await rawRequest<{ items: Sku[]; next_cursor: string | null }>({ method: "GET", path: "/v1/admin/skus", token, query: { status: "active", limit: 500, cursor } });
    if (!r.ok) break;
    out.push(...r.data.items);
    if (!r.data.next_cursor) break;
    cursor = r.data.next_cursor;
  }
  return out;
}
