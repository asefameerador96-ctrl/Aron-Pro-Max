// Seed rows and table definitions of the mock (see tables.ts). Ids are arbitrary but stable; zones are 14 to 19.
import type { Row, TableDef } from "./tables";

const T0 = "2026-10-01T04:00:00.000Z";
const row = (id: number, extra: Record<string, unknown>): Row => ({ id, version: 1, created_at: T0, updated_at: T0, status: "active", ...extra });

type GeoLevel = "wing" | "division" | "territory" | "house" | "zone";

export function seedTables(): Record<string, Row[]> {
  const geo = (level: GeoLevel, id: number, code: string, name: string, parent_id: number | null, extra: Record<string, unknown> = {}) =>
    row(id, { level, code, name, name_bn: null, parent_id, dep_name: null, email: null, address: null, pda_contact_no: null, ...extra });
  return {
    geo: [
      geo("wing", 1, "W-1", "Dhaka Wing", null, { name_bn: "ঢাকা উইং" }),
      geo("wing", 2, "W-2", "Chattogram Wing", null),
      geo("division", 3, "D-1", "Dhaka North", 1),
      geo("division", 4, "D-2", "Dhaka South", 1),
      geo("division", 5, "D-3", "Chattogram Metro", 2),
      geo("territory", 6, "T-334", "Banani", 3),
      geo("territory", 7, "T-335", "Mirpur", 3),
      geo("territory", 8, "T-336", "Motijheel", 4),
      geo("territory", 9, "T-401", "Agrabad", 5),
      geo("house", 10, "H-1", "Banani House", 6),
      geo("house", 11, "H-2", "Mirpur House", 7),
      geo("house", 12, "H-3", "Motijheel House", 8),
      geo("house", 13, "H-4", "Agrabad House", 9),
      geo("zone", 14, "Z-334-1", "Banani Zone 1", 10, { dep_name: "Banani Depot" }),
      geo("zone", 15, "Z-335-1", "Mirpur Zone 1", 11, { dep_name: "Mirpur Depot" }),
      geo("zone", 16, "Z-336-1", "Motijheel Zone 1", 12),
      geo("zone", 17, "Z-401-1", "Agrabad Zone 1", 13),
      geo("zone", 18, "Z-401-2", "Agrabad Zone 2", 13),
      geo("zone", 19, "Z-335-2", "Mirpur Zone 2", 11),
    ],
    clusters: [
      row(1, { zone_id: 14, name: "Banani Market", cluster_type: "market" }),
      row(2, { zone_id: 14, name: "Gulshan-1 Circle", cluster_type: "urban" }),
      row(3, { zone_id: 15, name: "Mirpur-10", cluster_type: "urban" }),
      row(4, { zone_id: 15, name: "Uttara Sector 7", cluster_type: null }),
      row(5, { zone_id: 16, name: "Savar Bazar", cluster_type: "semi_urban" }),
      row(6, { zone_id: 16, name: "Ashulia Haat", cluster_type: "rural" }),
      row(7, { zone_id: 16, name: "Hatirjheel", cluster_type: "urban" }),
    ],
  };
}

interface Holder {
  tables: Record<string, Row[]>;
  nextId: number;
}

const GEO_FIELDS = ["name", "name_bn", "parent_id", "dep_name", "email", "address", "pda_contact_no"];
const like = (key: string) => (r: Row, v: string) => String(r[key] ?? "").toLowerCase().includes(v.toLowerCase());

export function tableDefs(h: Holder): TableDef[] {
  const nextId = () => h.nextId++;
  return [
    {
      collection: /^\/v1\/admin\/geo\/(wing|division|territory|house|zone)$/,
      item: /^\/v1\/admin\/geo\/(wing|division|territory|house|zone)\/(\d+)$/,
      select: (r, p) => r.level === p[0],
      stamp: (p) => ({ level: p[0], status: "active" }),
      rows: () => h.tables.geo!,
      create: { allowed: ["code", ...GEO_FIELDS], required: ["code", "name"], reason: true },
      patch: { allowed: [...GEO_FIELDS, "status"] },
      unique: [["code"]],
      maxLength: { name: 120, name_bn: 120, address: 300 },
      patterns: { code: /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/, pda_contact_no: /^\+?[0-9]{5,15}$/ },
      auditEntity: "geo_node",
      filters: { parent_id: (r, v) => r.parent_id === Number(v), status: (r, v) => r.status === v, q: (r, v) => like("name")(r, v) || like("code")(r, v) },
      nullables: ["name_bn", "parent_id", "dep_name", "email", "address", "pda_contact_no"],
      nextId,
    },
    {
      collection: /^\/v1\/admin\/clusters$/,
      item: /^\/v1\/admin\/clusters\/(\d+)$/,
      rows: () => h.tables.clusters!,
      create: { allowed: ["zone_id", "name", "cluster_type"], required: ["zone_id", "name"], reason: false },
      patch: { allowed: ["zone_id", "name", "cluster_type", "status"] },
      unique: [["zone_id", "name"]],
      maxLength: { name: 120, cluster_type: 60 },
      auditEntity: "cluster",
      filters: { zone_id: (r, v) => r.zone_id === Number(v), status: (r, v) => r.status === v, q: like("name") },
      defaults: { status: "active" },
      nullables: ["cluster_type"],
      nextId,
    },
  ];
}
