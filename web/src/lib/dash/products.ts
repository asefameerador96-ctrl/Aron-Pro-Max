// Product browse pages (F-WEB-004 to F-WEB-007, F-WEB-009): flat rows per level with the ancestor names, and the expandable tree.
import type { Schemas } from "@/contract/types";

export type Level = "category" | "segment" | "brand" | "variant";
export const LEVELS: readonly Level[] = ["category", "segment", "brand", "variant"];
export type Node = Schemas["ProductNode"];

export interface ProductRow {
  id: number;
  category?: string;
  segment?: string;
  brand?: string;
  variant?: string;
  status: "active" | "inactive";
  sort: number;
}

export function isLevel(x: string): x is Level {
  return (LEVELS as readonly string[]).includes(x);
}

/** Rows for one level, each carrying the names of its ancestors; ordered by sort, then name. */
export function levelRows(level: Level, all: Record<Level, readonly Node[]>): ProductRow[] {
  const byId = new Map<number, Node>();
  for (const l of LEVELS) for (const nd of all[l]) byId.set(nd.id, nd);
  const chain = (nd: Node): Partial<Record<Level, string>> => {
    const out: Partial<Record<Level, string>> = {};
    let cur: Node | undefined = nd;
    for (let i = 0; cur && i < 5; i++) {
      out[cur.level] = cur.name;
      cur = cur.parent_id ? byId.get(cur.parent_id) : undefined;
    }
    return out;
  };
  return [...all[level]]
    .sort((a, b) => a.sort - b.sort || a.name.localeCompare(b.name))
    .map((nd) => ({ id: nd.id, ...chain(nd), status: nd.status === "active" ? "active" : "inactive", sort: nd.sort }));
}

export interface TreeNode {
  key: string;
  label: string;
  level: Level | "sku" | "root";
  status?: "active" | "inactive";
  children: TreeNode[];
}

/** All Products > Category > Segment > Brand > Variant > SKU. A SKU sits under its variant. */
export function buildTree(all: Record<Level, readonly Node[]>, skus: readonly Schemas["Sku"][], rootLabel: string): TreeNode {
  const kids = (level: Level, parent: number | null): Node[] => all[level].filter((x) => (x.parent_id ?? null) === parent).sort((a, b) => a.sort - b.sort);
  const mk = (level: Level, nd: Node): TreeNode => {
    const next = LEVELS[LEVELS.indexOf(level) + 1];
    const children: TreeNode[] = next ? kids(next, nd.id).map((c) => mk(next, c)) : skus.filter((s) => s.variant_id === nd.id).sort((a, b) => a.sort - b.sort).map((s) => ({ key: `sku-${s.id}`, label: s.short_name, level: "sku" as const, status: s.status === "active" ? "active" as const : "inactive" as const, children: [] }));
    return { key: `${level}-${nd.id}`, label: nd.name, level, status: nd.status === "active" ? "active" : "inactive", children };
  };
  return { key: "root", label: rootLabel, level: "root", children: kids("category", null).map((c) => mk("category", c)) };
}

export function countLeaves(tn: TreeNode): number {
  return tn.children.length === 0 ? (tn.level === "sku" ? 1 : 0) : tn.children.reduce((a, c) => a + countLeaves(c), 0);
}
