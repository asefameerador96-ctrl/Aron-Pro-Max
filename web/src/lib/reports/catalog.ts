// The web report pages (docs/evidence/manuals, minus the deferred programmes of docs/27). One entry per backlog row;
// the page, filters, export and PII gating are the same engine for all of them, so a report is data, not code.
import type { Role, Schemas } from "@/contract/types";
import { WEB_ROLES, type RoleList } from "@/lib/auth/roles";
import type { MessageKey } from "@/lib/i18n";

export type ReportKey = Schemas["ReportKey"];
export type FilterId = Schemas["ReportDefinition"]["filters"][number];

export type ReportArea = "sales" | "field_force" | "outlet" | "day_control" | "geo" | "finance" | "ops";

export interface WebReport {
  /** Backlog row this page satisfies. */
  row: string;
  /** URL segment: /reports/<slug>. */
  slug: string;
  key: ReportKey;
  titleKey: MessageKey;
  area: ReportArea;
  /** Filters shown (a subset of what the server's registry honours; the server stays the authority). */
  filters: readonly FilterId[];
  /** Roles that see it in the menu; the server still decides what rows come back. */
  roles?: RoleList;
  /** One-day report (a single `date`) rather than a from/to range. */
  singleDate?: boolean;
  /** The report offers a print view (DS-RRS, F-WEB-053). */
  print?: boolean;
  /** Default grouping when the report supports it. */
  grouping?: "total" | "day";
  /** Exports are allowed. Defaults to true; every export is a server-logged `xlsx` call. */
  excel?: boolean;
  /** The report offers "Download PDF" (an export job, always 202). */
  pdf?: boolean;
  /** Show one table per distinct value of this column (QC: Market and Warehouse apart). */
  splitBy?: string;
  /** Link a cell to another report filtered by an id carried in the row (DSS: route to outlet-wise sales). */
  drill?: { column: string; idColumn: string; to: string; param: "route" | "zone" | "territory" };
  /** A bar chart over two columns, drawn only when both exist in the result (histograms and trends). */
  chart?: { label: string; value: string };
  /** The geo-validation summary strip (mock, suspicious and force-sale counts) above the table. */
  geoStrip?: boolean;
}

const RANGE = ["period", "geo"] as const satisfies readonly FilterId[];
const DAY = ["period", "geo"] as const satisfies readonly FilterId[];

export const WEB_REPORTS: readonly WebReport[] = [
  { row: "F-WEB-002", slug: "retailers", key: "retailer-list", titleKey: "report.retailers", area: "outlet", filters: ["geo", "active_status", "sub_channels", "category", "outlet_code"] },
  { row: "F-WEB-008", slug: "skus", key: "sku-list", titleKey: "report.skus", area: "sales", filters: ["active_status", "category", "products"] },
  { row: "F-WEB-011", slug: "task-planner", key: "task-planner", titleKey: "report.task_planner", area: "field_force", filters: RANGE },
  { row: "F-WEB-012", slug: "geo-capture", key: "by-route-geo-capture", titleKey: "report.geo_capture", area: "geo", filters: ["geo"] },
  { row: "F-WEB-013", slug: "std-memo", key: "std-memo", titleKey: "report.std_memo", area: "sales", filters: ["period", "geo", "category", "product_type", "products", "date_grouping", "std_criteria", "memo_criteria", "field_force_type"], grouping: "total" },
  { row: "F-WEB-014", slug: "sr-efficiency", key: "sr-efficiency", titleKey: "report.sr_efficiency", area: "field_force", filters: [...RANGE, "field_force_type"] },
  { row: "F-WEB-015", slug: "route-std", key: "route-std", titleKey: "report.route_std", area: "sales", filters: [...RANGE, "category", "product_type", "products"] },
  { row: "F-WEB-016", slug: "data-entry-log", key: "data-entry-log", titleKey: "report.data_entry_log", area: "ops", filters: DAY, singleDate: true },
  { row: "F-WEB-017", slug: "final-submit-log", key: "final-submit-log", titleKey: "report.final_submit_log", area: "day_control", filters: DAY, singleDate: true },
  { row: "F-WEB-018", slug: "cpr-bsr", key: "route-bsr-cpr", titleKey: "report.cpr_bsr", area: "sales", filters: [...RANGE, "category"] },
  { row: "F-WEB-019", slug: "by-outlet", key: "by-outlet", titleKey: "report.by_outlet", area: "outlet", filters: [...RANGE, "category", "product_type", "active_status", "sub_channels", "outlet_code"] },
  { row: "F-WEB-021", slug: "gigo", key: "gigo", titleKey: "report.gigo", area: "field_force", filters: DAY, singleDate: true },
  { row: "F-WEB-024", slug: "by-outlet-by-day", key: "by-outlet-by-day", titleKey: "report.by_outlet_by_day", area: "outlet", filters: [...RANGE, "outlet_code"] },
  { row: "F-WEB-025", slug: "online-offline", key: "online-offline", titleKey: "report.online_offline", area: "ops", filters: RANGE },
  { row: "F-WEB-026", slug: "free-sample", key: "free-sample", titleKey: "report.free_sample", area: "sales", filters: [...RANGE, "products"] },
  { row: "F-WEB-027", slug: "tso-top-sheet", key: "tso-top-sheet", titleKey: "report.tso_top_sheet", area: "field_force", filters: RANGE },
  { row: "F-WEB-031", slug: "sr-outlets", key: "sr-outlets", titleKey: "report.sr_outlets", area: "outlet", filters: RANGE },
  { row: "F-WEB-036", slug: "leaderboard", key: "leaderboard", titleKey: "report.leaderboard", area: "field_force", filters: ["period", "geo", "product_type", "location"] },
  { row: "F-WEB-044", slug: "suspicious-location", key: "suspicious-location", titleKey: "report.suspicious_location", area: "geo", filters: [...RANGE, "date_grouping"], grouping: "day", geoStrip: true, chart: { label: "business_date", value: "suspicious" } },
  { row: "F-WEB-053", slug: "ds-rrs", key: "ds-rrs", titleKey: "report.ds_rrs", area: "finance", filters: DAY, singleDate: true, print: true },
  { row: "F-WEB-054", slug: "amo-call", key: "amo-call", titleKey: "report.amo_call", area: "field_force", filters: [...RANGE, "date_grouping"], grouping: "day" },
  { row: "F-WEB-055", slug: "dss", key: "dss", titleKey: "report.dss", area: "sales", filters: [...RANGE, "category", "products", "sub_channels", "location"], drill: { column: "route_name", idColumn: "route_id", to: "by-outlet", param: "route" } },
  { row: "F-WEB-056", slug: "route-memo", key: "route-memo", titleKey: "report.route_memo", area: "sales", filters: [...RANGE, "category", "products"] },
  { row: "F-WEB-061", slug: "qc", key: "qc-report", titleKey: "report.qc", area: "ops", filters: RANGE, pdf: true, splitBy: "source" },
  { row: "F-WEB-062", slug: "route-qc", key: "route-qc", titleKey: "report.route_qc", area: "ops", filters: RANGE },
  { row: "F-WEB-064", slug: "memo-number-gaps", key: "memo-number-gaps", titleKey: "report.memo_gaps", area: "ops", filters: RANGE },
  { row: "F-WEB-067", slug: "geofence-calibration", key: "geofence-calibration", titleKey: "report.geofence_calibration", area: "geo", filters: RANGE, chart: { label: "distance_band_m", value: "visits" } },
];

export function reportBySlug(slug: string): WebReport | undefined {
  return WEB_REPORTS.find((r) => r.slug === slug);
}

/** Roles that may open report pages at all (the server's own ACL narrows further). */
export const REPORT_ROLES: RoleList = WEB_ROLES;

export function canSeeReport(r: WebReport, role: Role): boolean {
  return (r.roles ?? REPORT_ROLES).includes(role);
}
