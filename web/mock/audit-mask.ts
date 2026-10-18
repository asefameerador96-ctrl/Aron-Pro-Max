// Mock of listAudit's masking (backend AuditRead.kt AuditMask, D-AUTHZ-AUDIT), so e2e sees what each role sees on the server:
// never-show members are masked for everyone; personal data is masked for everyone (no token carries the PII grant yet);
// ANALYST and SUPPORT see only changed members, a value only when the entity's allowlist names it, and the reason masked.
import type { AuditEntry, Role } from "../src/contract/types";

export const MASK = "[masked]";
type Image = NonNullable<AuditEntry["before"]>;

const NEVER_SHOW = new Set(["prefix", "token_id", "password_hash", "temporary_password", "otp", "otp_cipher", "otp_sha256", "secret", "secret_cipher", "token_sha256", "sig", "recovery_codes"]);
const SECRETISH = /(secret|password|passwd|hash|token|otp|nonce|cipher|private|jti|refresh|totp|seed|signature|proof)/i;
const PII = new Set(["full_name", "phone", "email", "contact_number", "owner_name", "address", "pda_contact_no", "dep_name", "nid", "tin", "trade_license", "lat", "lng", "provisional_lat", "provisional_lng"]);
const PII_LIKE = /(phone|e_?mail|contact|owner_name|full_name|address|dep_name|trade_license|(^|_)nid($|_)|(^|_)tin($|_)|(^|_)(lat|lng|latitude|longitude)$)/i;
const GEO = ["code", "name", "name_bn", "wing_id", "division_id", "territory_id", "house_id", "status", "version"];

/** The subset of the server's allowlist that the mock's writers produce (unlisted entities are default deny, as on the server). */
const PLAIN: Record<string, readonly string[]> = {
  cfg_change: ["value"],
  cfg_version: ["key", "batch_uuid"],
  tutorial: ["version", "status", "kind", "sort", "roles"],
  feedback: ["status"],
  price_batch: ["status", "valid_from", "rows", "max_change_pct", "price_list_version", "submitted_by", "backdated"],
  calendar_holiday: ["id", "date", "scope_type", "scope_id", "kind", "selling_day", "name_en", "name_bn"],
  user: ["id", "username", "role", "designation", "employee_code", "locale", "home_zone_id", "pilot", "status", "disabled_at", "scope_version", "version"],
  route: ["code", "name", "display_label", "zone_id", "kind", "visit_kind", "visit_days_mask", "sequence_no", "status", "version", "effective_from"],
  outlet: ["code", "name", "name_bn", "zone_id", "route_id", "cluster_id", "channel", "sub_channel_id", "geo_class", "outlet_kind", "status", "visit_sequence", "external_ref", "id", "version", "location_basis", "location_confirmed", "closed_at", "batch_uuid"],
  cluster: ["zone_id", "name", "cluster_type", "status", "version"],
  geo_wing: GEO, geo_division: GEO, geo_territory: GEO, geo_house: GEO, geo_zone: GEO,
};

const neverShow = (k: string) => NEVER_SHOW.has(k.toLowerCase()) || SECRETISH.test(k);
const personal = (k: string) => PII.has(k.toLowerCase()) || (PII_LIKE.test(k) && !/_(id|uuid)$/i.test(k));
const full = (o: Image | null | undefined, pii: boolean) => (o ? Object.fromEntries(Object.entries(o).map(([k, v]) => [k, neverShow(k) || (personal(k) && !pii) ? MASK : v])) : null);

/** One stored row as `role` may read it; `pii`: the reader is inside a PII reveal window (ADMIN/SUPERADMIN only). */
export function maskAudit(e: AuditEntry, role: Role, pii = false): AuditEntry {
  if (role === "ADMIN" || role === "SUPERADMIN") return { ...e, before: full(e.before, pii), after: full(e.after, pii) };
  const allowed = new Set(PLAIN[e.entity] ?? []);
  const keys = new Set([...Object.keys(e.before ?? {}), ...Object.keys(e.after ?? {})]);
  const changed = new Set([...keys].filter((k) => e.before?.[k] !== e.after?.[k]));
  const side = (o: Image | null | undefined) => (o ? Object.fromEntries(Object.entries(o).filter(([k]) => changed.has(k)).map(([k, v]) => [k, allowed.has(k) && !neverShow(k) && !personal(k) ? v : MASK])) : null);
  return { ...e, before: side(e.before), after: side(e.after), reason: e.reason === null ? null : MASK };
}
