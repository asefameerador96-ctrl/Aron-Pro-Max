// Zone ids under a node, so the route-level tables (the daily-tracking read is scoped by the token only) can follow the
// Filter. The server re-checks reach on every read; this only narrows what the page asks about.
import { getSummary } from "@/lib/dash/server";

export interface PickedNode {
  level?: string;
  node_id?: number;
}

/** Zone ids at or under the node: the node itself when it is a zone, else its descendants found through the summary children (bounded). */
export async function zonesUnder(token: string, node: PickedNode, date: string): Promise<Set<number> | null> {
  if (node.level === undefined || node.node_id === undefined) return null;
  if (node.level === "zone" || node.level === "route") return new Set([node.node_id]);
  const out = new Set<number>();
  let frontier: { level: string; id: number }[] = [{ level: node.level, id: node.node_id }];
  for (let depth = 0; depth < 4 && frontier.length > 0 && out.size < 2000; depth++) {
    const next: { level: string; id: number }[] = [];
    await Promise.all(
      frontier.map(async (f) => {
        const r = await getSummary(token, { from: date, to: date, level: f.level, node_id: f.id });
        if (!r.ok) return;
        for (const c of r.data.children) {
          if (c.node.type === "zone") out.add(c.node.id);
          else next.push({ level: c.node.type, id: c.node.id });
        }
      }),
    );
    frontier = next;
  }
  return out;
}
