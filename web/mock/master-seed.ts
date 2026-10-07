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
    routes: [
      row(1, { code: "R-334-01", name: "Banani Daily", display_label: "Daily", zone_id: 14, kind: "sr", visit_kind: "daily", visit_days_mask: 127, sequence_no: 1 }),
      row(2, { code: "R-334-02", name: "Banani 3F", display_label: "(Sat, Mon, Wed)", zone_id: 14, kind: "sr", visit_kind: "3f", visit_days_mask: 21, sequence_no: 2 }),
      row(3, { code: "R-335-01", name: "Mirpur Daily", display_label: "Daily", zone_id: 15, kind: "sr", visit_kind: "daily", visit_days_mask: 127, sequence_no: 1 }),
      row(4, { code: "R-335-02", name: "Mirpur 2F", display_label: "(Sun, Tue)", zone_id: 15, kind: "sr", visit_kind: "2f", visit_days_mask: 10, sequence_no: 2 }),
      row(5, { code: "A-334-01", name: "Banani AMO", display_label: null, zone_id: 14, kind: "amo", visit_kind: null, visit_days_mask: 127, sequence_no: null }),
    ],
    users: [
      row(1001, { username: "sr334001", full_name: "Testing Banani", role: "SR", designation: "SR", employee_code: "E1001", phone: null, email: null, locale: "bn", home_zone_id: 14, pilot: false, status: "active", mfa_enabled: false, last_login_at: null }),
      row(1002, { username: "sr334002", full_name: "Karim Mia", role: "SR", designation: "SR", employee_code: "E1002", phone: null, email: null, locale: "bn", home_zone_id: 14, pilot: true, status: "active", mfa_enabled: false, last_login_at: null }),
      row(1003, { username: "sr335001", full_name: "Rahim Sheikh", role: "SR", designation: "SR", employee_code: "E1003", phone: null, email: null, locale: "bn", home_zone_id: 15, pilot: false, status: "active", mfa_enabled: false, last_login_at: null }),
      row(2001, { username: "tso334", full_name: "Rahim Uddin", role: "TSO", designation: "TSO", employee_code: "E2001", phone: null, email: null, locale: "bn", home_zone_id: null, pilot: false, status: "active", mfa_enabled: false, last_login_at: null }),
      row(3001, { username: "admin1", full_name: "Salma Akter", role: "ADMIN", designation: null, employee_code: null, phone: null, email: null, locale: "bn", home_zone_id: null, pilot: false, status: "active", mfa_enabled: true, last_login_at: null }),
      row(4001, { username: "locked1", full_name: "Locked User", role: "TSO", designation: null, employee_code: null, phone: null, email: null, locale: "bn", home_zone_id: null, pilot: false, status: "disabled", mfa_enabled: false, last_login_at: null }),
    ],
    assignments: [
      row(1, { route_id: 1, user_id: 1001, kind: "primary", valid_from: "2026-09-01", valid_to: null, reason: null }),
      row(2, { route_id: 2, user_id: 1002, kind: "primary", valid_from: "2026-09-01", valid_to: null, reason: null }),
      row(3, { route_id: 3, user_id: 1003, kind: "primary", valid_from: "2026-09-01", valid_to: "2026-10-01", reason: null }),
    ],
    product: [
      row(1, { level: "category", parent_id: null, code: "CIG", name: "Cigarette", name_bn: "সিগারেট", sort: 1 }),
      row(2, { level: "category", parent_id: null, code: "BIDI", name: "Bidi", name_bn: null, sort: 2 }),
      row(3, { level: "segment", parent_id: 1, code: null, name: "Premium", name_bn: null, sort: 1 }),
      row(4, { level: "segment", parent_id: 1, code: null, name: "Value", name_bn: null, sort: 2 }),
      row(5, { level: "brand", parent_id: 3, code: "MAXR", name: "Max Royal", name_bn: null, sort: 1 }),
      row(6, { level: "brand", parent_id: 4, code: "VAL", name: "Value Gold", name_bn: null, sort: 1 }),
      row(7, { level: "variant", parent_id: 5, code: null, name: "Max Royal 10s", name_bn: null, sort: 1 }),
      row(8, { level: "variant", parent_id: 5, code: null, name: "Max Royal 20s", name_bn: null, sort: 2 }),
    ],
    skus: [
      row(1, { code: "MaxR-10S", variant_id: 7, category_code: "cigarette", name: "Max Royal 10s", short_name: "MaxR 10", name_bn: null, base_unit: "stick", base_per_pack: 10, entry_unit_default: "pack", report_unit: "pack", report_factor: "0.100", sort: 1 }),
      row(2, { code: "MaxR-20S", variant_id: 8, category_code: "cigarette", name: "Max Royal 20s", short_name: "MaxR 20", name_bn: null, base_unit: "stick", base_per_pack: 20, entry_unit_default: "pack", report_unit: "pack", report_factor: "0.050", sort: 2 }),
    ],
    outlets: [
      row(1, { code: "DHK-334-001", name: "Banani Store", name_bn: null, owner_name: "Md. Rahim", contact_number: null, address: "Road 11, Banani", zone_id: 14, route_id: 1, cluster_id: 1, channel: "GT", sub_channel_id: 11, geo_class: "Urban", lat: 23.7937, lng: 90.4066, location_confirmed: true, outlet_kind: "retail", price_type: "regular", status: "active", visit_sequence: 1, external_ref: null }),
      row(2, { code: "DHK-334-002", name: "Gulshan Corner Shop", name_bn: null, owner_name: "Abdul Karim", contact_number: null, address: null, zone_id: 14, route_id: 1, cluster_id: 2, channel: "GT", sub_channel_id: 11, geo_class: "Urban", lat: 23.7925, lng: 90.4078, location_confirmed: false, outlet_kind: "retail", price_type: "regular", status: "active", visit_sequence: 2, external_ref: null }),
      row(3, { code: "DHK-334-003", name: "Mirpur Wholesale", name_bn: null, owner_name: "Jahangir Alam", contact_number: null, address: "Mirpur-10", zone_id: 15, route_id: 3, cluster_id: 3, channel: "GT", sub_channel_id: 12, geo_class: "Urban", lat: null, lng: null, location_confirmed: false, outlet_kind: "wholesale", price_type: "wholesale", status: "active", visit_sequence: null, external_ref: null }),
      row(4, { code: "DHK-335-004", name: "Wrongly Closed Tea Stall", name_bn: null, owner_name: "Salam Mia", contact_number: null, address: null, zone_id: 15, route_id: 4, cluster_id: 3, channel: "GT", sub_channel_id: 11, geo_class: "SemiUrban", lat: null, lng: null, location_confirmed: false, outlet_kind: "retail", price_type: "regular", status: "closed", visit_sequence: null, external_ref: null }),
      row(5, { code: "DHK-335-005", name: "Uttara Pan Shop", name_bn: null, owner_name: "Kamal Hossain", contact_number: null, address: null, zone_id: 15, route_id: null, cluster_id: 4, channel: "GT", sub_channel_id: null, geo_class: null, lat: null, lng: null, location_confirmed: false, outlet_kind: "retail", price_type: "regular", status: "active", visit_sequence: null, external_ref: null }),
    ],
    outletRequests: [
      { request_uuid: "11111111-1111-4111-8111-111111111111", request_type: "new", status: "pending", outlet_id: null, outlet_name: "New Corner Mart", route_id: 1, cluster_id: 1, requested_by_user_id: 1001, requested_at: "2026-10-05T04:00:00.000Z", rejection_reason: null, proposed: { name: "New Corner Mart", owner_name: "Rubel Ahmed" }, photos: [], events: [], version: 1 },
      { request_uuid: "22222222-2222-4222-8222-222222222222", request_type: "new", status: "verified", outlet_id: null, outlet_name: "Verified Fresh Store", route_id: 1, cluster_id: 2, requested_by_user_id: 1001, verified_by_user_id: 1003, requested_at: "2026-10-04T04:00:00.000Z", rejection_reason: null, proposed: { name: "Verified Fresh Store", owner_name: "Sumon Das", cluster_id: 2 }, photos: [], events: [], version: 1 },
      { request_uuid: "33333333-3333-4333-8333-333333333333", request_type: "close", status: "verified", outlet_id: 2, outlet_name: "Gulshan Corner Shop", route_id: 1, cluster_id: 2, requested_by_user_id: 1001, verified_by_user_id: 1003, requested_at: "2026-10-04T05:00:00.000Z", rejection_reason: null, proposed: { close_reason_code: "permanent" }, photos: [], events: [], version: 1 },
      { request_uuid: "44444444-4444-4444-8444-444444444444", request_type: "info", status: "pending", outlet_id: 1, outlet_name: "Banani Store", route_id: 1, cluster_id: 1, requested_by_user_id: 1002, requested_at: "2026-10-05T06:00:00.000Z", rejection_reason: null, proposed: { owner_name: "Md. Rahim Uddin" }, photos: [], events: [], version: 1 },
    ] as unknown as Row[],
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

const overlaps = (aFrom: string, aTo: string | null, bFrom: string, bTo: string | null) => aFrom < (bTo ?? "9999-12-31") && bFrom < (aTo ?? "9999-12-31");
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
      collection: /^\/v1\/admin\/product-nodes\/(category|segment|brand|variant)$/,
      item: /^\/v1\/admin\/product-nodes\/(category|segment|brand|variant)\/(\d+)$/,
      select: (r, p) => r.level === p[0],
      stamp: (p) => ({ level: p[0], status: "active" }),
      rows: () => h.tables.product!,
      create: { allowed: ["parent_id", "code", "name", "name_bn", "sort"], required: ["name", "sort"], reason: false },
      patch: { allowed: ["parent_id", "name", "name_bn", "sort", "status"] },
      maxLength: { name: 120, name_bn: 120, code: 40 },
      auditEntity: "product_node",
      filters: { parent_id: (r, v) => r.parent_id === Number(v), status: (r, v) => r.status === v },
      nullables: ["parent_id", "code", "name_bn"],
      nextId,
    },
    {
      collection: /^\/v1\/admin\/skus$/,
      item: /^\/v1\/admin\/skus\/(\d+)$/,
      rows: () => h.tables.skus!,
      create: { allowed: ["code", "variant_id", "category_code", "name", "short_name", "name_bn", "base_unit", "base_per_pack", "entry_unit_default", "report_unit", "report_factor", "sort"], required: ["code", "variant_id", "category_code", "name", "short_name", "base_unit", "base_per_pack", "entry_unit_default", "report_factor", "sort"], reason: false },
      patch: { allowed: ["name", "short_name", "name_bn", "report_unit", "report_factor", "sort", "status", "thumbnail_media_uuid"] },
      unique: [["code"]],
      maxLength: { name: 120, short_name: 20 },
      patterns: { code: /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/, report_factor: /^-?\d{1,13}(\.\d{1,3})?$/ },
      auditEntity: "sku",
      filters: { status: (r, v) => r.status === v, q: (r, v) => like("name")(r, v) || like("code")(r, v) },
      defaults: { status: "active" },
      nullables: ["name_bn", "report_unit"],
      nextId,
    },
    {
      collection: /^\/v1\/admin\/outlets$/,
      item: /^\/v1\/admin\/outlets\/(\d+)$/,
      rows: () => h.tables.outlets!,
      create: { allowed: ["code", "name", "name_bn", "owner_name", "contact_number", "address", "zone_id", "route_id", "cluster_id", "channel", "sub_channel_id", "geo_class", "lat", "lng", "outlet_kind", "external_ref"], required: ["name", "owner_name", "zone_id", "cluster_id", "channel", "outlet_kind"], reason: true },
      patch: { allowed: ["name", "name_bn", "owner_name", "contact_number", "address", "route_id", "cluster_id", "channel", "sub_channel_id", "geo_class", "lat", "lng", "outlet_kind", "status", "visit_sequence", "external_ref"] },
      maxLength: { name: 120, owner_name: 120, address: 300 },
      patterns: { code: /^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$/, contact_number: /^01[3-9]\d{8}$/ },
      unique: [["code"]],
      auditEntity: "outlet",
      filters: { zone_id: (r, v) => r.zone_id === Number(v), route_id: (r, v) => r.route_id === Number(v), cluster_id: (r, v) => r.cluster_id === Number(v), status: (r, v) => r.status === v, q: (r, v) => like("name")(r, v) || like("code")(r, v) },
      defaults: { status: "active", location_confirmed: false, price_type: "regular", visit_sequence: null },
      nullables: ["code", "name_bn", "contact_number", "address", "route_id", "sub_channel_id", "geo_class", "lat", "lng", "external_ref"],
      derive: (r) => ({ code: r.code ?? `OUT-${r.id}` }),
      nextId,
    },
    {
      collection: /^\/v1\/outlet-requests$/,
      item: /^\/v1\/outlet-requests\/([0-9a-f-]{36})$/,
      idField: "request_uuid",
      rows: () => h.tables.outletRequests!,
      create: { allowed: [], required: [], reason: false },
      patch: { allowed: [] },
      auditEntity: "outlet_request",
      filters: { request_type: (r, v) => r.request_type === v, status: (r, v) => r.status === v },
      nextId,
      actions: [
        {
          path: /^\/v1\/outlet-requests\/([0-9a-f-]{36})\/verify$/,
          required: ["sub_channel_id"],
          allowed: ["sub_channel_id", "geo_class", "note"],
          run: (row, _b) => (row.status !== "pending" ? { status: 409, body: null, code: "ERR_REQUEST_STATE" } : { status: 200, body: { ...row, status: "verified" }, mutate: { status: "verified" } }),
        },
        {
          path: /^\/v1\/outlet-requests\/([0-9a-f-]{36})\/approve$/,
          required: ["change_reason"],
          allowed: ["outlet_code", "route_id", "change_reason"],
          run: (row, b, actor) => {
            if (row.status !== "verified") return { status: 409, body: null, code: "ERR_REQUEST_STATE" };
            if (row.requested_by_user_id === actor || row.verified_by_user_id === actor) return { status: 409, body: null, code: "ERR_SEPARATION_OF_DUTIES" };
            return { status: 200, body: { ...row, status: "approved" }, mutate: { status: "approved", approved_by_user_id: actor, approved_at: new Date().toISOString() } };
          },
          extra: (row, b) => {
            const outlets = h.tables.outlets!;
            if (row.request_type === "new") {
              const id = h.nextId++;
              const p = row.proposed as Record<string, unknown>;
              outlets.push({ id, version: 1, created_at: T0, updated_at: T0, status: "active", code: (b.outlet_code as string | null) ?? `NEW-${id}`, name: p.name, owner_name: p.owner_name, zone_id: 14, route_id: (b.route_id as number | null) ?? row.route_id, cluster_id: p.cluster_id ?? 1, channel: "GT", outlet_kind: "retail", price_type: "regular", location_confirmed: false } as Row);
              row.resulting_outlet_id = id;
            }
            if (row.request_type === "close") {
              const o = outlets.find((x) => x.id === row.outlet_id);
              if (o) { o.status = "closed"; o.version++; }
            }
          },
        },
        {
          path: /^\/v1\/outlet-requests\/([0-9a-f-]{36})\/reject$/,
          required: ["reason"],
          allowed: ["reason"],
          run: (row, b) => (row.status !== "pending" && row.status !== "verified" ? { status: 409, body: null, code: "ERR_REQUEST_STATE" } : { status: 200, body: { ...row, status: "rejected" }, mutate: { status: "rejected", rejection_reason: b.reason } }),
        },
      ],
    },
    {
      collection: /^\/v1\/admin\/routes$/,
      item: /^\/v1\/admin\/routes\/(\d+)$/,
      rows: () => h.tables.routes!,
      create: { allowed: ["code", "name", "display_label", "zone_id", "kind", "visit_kind", "visit_days_mask", "sequence_no"], required: ["code", "name", "zone_id", "kind", "visit_days_mask"], reason: false },
      patch: { allowed: ["name", "display_label", "zone_id", "visit_kind", "visit_days_mask", "sequence_no", "status", "effective_from"] },
      unique: [["code"]],
      maxLength: { name: 120, display_label: 60 },
      patterns: { code: /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/ },
      auditEntity: "route",
      filters: { zone_id: (r, v) => r.zone_id === Number(v), status: (r, v) => r.status === v, q: (r, v) => like("name")(r, v) || like("code")(r, v), territory_id: (r, v) => { const z = h.tables.geo!.find((g) => g.id === r.zone_id); const hs = h.tables.geo!.find((g) => g.id === z?.parent_id); return hs?.parent_id === Number(v); } },
      defaults: { status: "active" },
      nullables: ["display_label", "visit_kind", "sequence_no"],
      nextId,
    },
    {
      collection: /^\/v1\/admin\/users$/,
      item: /^\/v1\/admin\/users\/(\d+)$/,
      rows: () => h.tables.users!,
      create: { allowed: ["username", "full_name", "role", "designation", "employee_code", "phone", "email", "locale", "home_zone_id", "pilot"], required: ["username", "full_name", "role", "locale"], reason: false },
      patch: { allowed: ["full_name", "role", "designation", "employee_code", "phone", "email", "locale", "home_zone_id", "status", "pilot"] },
      unique: [["username"]],
      maxLength: { full_name: 120, designation: 60, employee_code: 40 },
      patterns: { username: /^[A-Za-z][A-Za-z0-9._-]{2,39}$/, phone: /^01[3-9]\d{8}$/ },
      auditEntity: "user",
      filters: { role: (r, v) => r.role === v, status: (r, v) => r.status === v, zone_id: (r, v) => r.home_zone_id === Number(v), q: (r, v) => like("username")(r, v) || like("full_name")(r, v) },
      defaults: { status: "active", mfa_enabled: false, pilot: false, last_login_at: null },
      nullables: ["designation", "employee_code", "phone", "email", "home_zone_id"],
      createResponse: (row) => ({ user: row, temporary_password: `Tmp-${row.id}-Xk29!pw`, temporary_password_expires_at: new Date(Date.now() + 86_400_000).toISOString() }),
      actions: [
        {
          roles: ["SUPPORT", "ADMIN", "SUPERADMIN"],
          path: /^\/v1\/admin\/users\/(\d+)\/credentials$/,
          required: ["action", "reason"],
          allowed: ["action", "reason"],
          run: (row, b) => {
            const action = b.action;
            if (action === "reset_password") return { status: 200, body: { action, done_at: new Date().toISOString(), temporary_password: `Tmp-${row.id}-Reset!99`, temporary_password_expires_at: new Date(Date.now() + 86_400_000).toISOString() } };
            if (action === "unlock" || action === "force_logout" || action === "reset_mfa") return { status: 200, body: { action, done_at: new Date().toISOString(), temporary_password: null, temporary_password_expires_at: null } };
            return { status: 400, body: null, code: "ERR_VALIDATION" };
          },
        },
      ],
      nextId,
    },
    {
      collection: /^\/v1\/admin\/route-assignments$/,
      rows: () => h.tables.assignments!,
      create: { allowed: ["route_id", "user_id", "kind", "valid_from", "valid_to", "reason"], required: ["route_id", "user_id", "kind", "valid_from"], reason: false, reasonMember: "reason" },
      patch: { allowed: [] },
      maxLength: { reason: 300 },
      auditEntity: "route_assignment",
      filters: { route_id: (r, v) => r.route_id === Number(v), user_id: (r, v) => r.user_id === Number(v), valid_on: (r, v) => String(r.valid_from) <= v && (r.valid_to === null || String(r.valid_to) > v) },
      nullables: ["valid_to", "reason"],
      validateCreate: (b, rows) => (b.kind === "primary" && rows.some((r) => r.route_id === b.route_id && r.kind === "primary" && overlaps(String(r.valid_from), (r.valid_to as string | null) ?? null, String(b.valid_from), (b.valid_to as string | null) ?? null)) ? { status: 409, code: "ERR_MASTER_OVERLAP" } : null),
      actions: [
        {
          path: /^\/v1\/admin\/route-assignments\/(\d+)\/end$/,
          required: ["valid_to", "reason"],
          allowed: ["valid_to", "reason"],
          run: (row, b) => (String(b.valid_to) < String(row.valid_from) ? { status: 409, body: null, code: "ERR_REQUEST_STATE" } : { status: 200, body: { ...row, valid_to: b.valid_to }, mutate: { valid_to: b.valid_to } }),
        },
      ],
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

type Item = { code: string; label_en: string; label_bn: string | null; sort: number; attrs?: Record<string, string | number | boolean | null>; valid_from?: string; valid_to?: string | null };
const it = (code: string, label_en: string, label_bn: string | null, sort: number, attrs?: Item["attrs"]): Item => ({ code, label_en, label_bn, sort, ...(attrs ? { attrs } : {}), valid_from: "2026-01-01", valid_to: null });

export function seedCodeLists(): Record<string, Item[]> {
  const qc = (code: string, en: string, bn: string, sort: number, group: string, applies_to = "app") => it(code, en, bn, sort, { group, applies_to });
  return {
    channel: [it("retail", "Retail", "খুচরা", 1), it("wholesale", "Wholesale", "পাইকারি", 2)],
    sub_channel: [it("grocery", "Grocery", "মুদি", 1), it("tea_stall", "Tea stall", "চায়ের দোকান", 2)],
    geo_class: [it("urban", "Urban", "শহর", 1), it("semi_urban", "Semi-urban", "উপশহর", 2), it("rural", "Rural", "গ্রাম", 3), it("hill", "Hill", "পাহাড়", 4)],
    task_type: [it("oos", "Out of stock", "স্টক নেই", 1, { roles: "AMO,TSO" }), it("general", "General", "সাধারণ", 2, { roles: "AMO,TSO" }), it("irregular_visit", "Irregular visit", "অনিয়মিত ভিজিট", 3, { roles: "TSO" })],
    qc_fault_type: [
      qc("broken_stick", "Broken stick", "ভাঙা স্টিক", 1, "MFC"),
      qc("loose_filter", "Loose filter", "ঢিলা ফিল্টার", 2, "MFC"),
      qc("under_filled", "Under-filled", "কম তামাক", 3, "MFC"),
      qc("pack_damage", "Pack damage", "প্যাক নষ্ট", 4, "MFC"),
      qc("print_defect", "Print defect", "ছাপার ত্রুটি", 5, "MFC"),
      qc("stale", "Stale stock", "পুরনো স্টক", 6, "MKT"),
      qc("wet", "Wet or damp", "ভেজা", 7, "MKT"),
      qc("pest", "Pest damage", "পোকা", 8, "MKT"),
      qc("counterfeit", "Counterfeit", "নকল", 9, "MKT"),
      qc("seal_broken", "Seal broken", "সিল ভাঙা", 10, "MKT"),
      qc("other", "Other", "অন্যান্য", 11, "MKT", "web"),
    ],
    force_reason: [it("gps_weak", "GPS signal weak", "জিপিএস দুর্বল", 1), it("outlet_moved", "Outlet moved", "দোকান সরেছে", 2)],
    edit_reason: [it("wrong_qty", "Wrong quantity", "ভুল পরিমাণ", 1)],
    void_reason: [it("duplicate", "Duplicate memo", "ডুপ্লিকেট মেমো", 1)],
    visit_outcome: [it("sold", "Sold", "বিক্রি", 1), it("no_sale", "No sale", "বিক্রি নেই", 2)],
    skip_reason: [it("closed", "Outlet closed", "দোকান বন্ধ", 1), it("not_reached", "Not reached", "যাওয়া হয়নি", 2)],
    day_exception_reason: [it("rain", "Heavy rain", "ভারী বৃষ্টি", 1), it("hartal", "Hartal", "হরতাল", 2)],
    stock_variance_reason: [it("damaged", "Damaged", "নষ্ট", 1)],
    leave_type: [it("casual", "Casual", "নৈমিত্তিক", 1)],
    feedback_category: [it("app", "App problem", "অ্যাপের সমস্যা", 1)],
    payment_mode: [it("cash", "Cash", "নগদ", 1)],
    outlet_close_reason: [it("permanent", "Closed for good", "চিরতরে বন্ধ", 1)],
    submit_void_reason: [it("mistake", "Submitted by mistake", "ভুলে সাবমিট", 1)],
  };
}
