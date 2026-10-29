// One line-icon set for the sidebar (PARITY-SPEC s4: white line icons): 24 px grid, 1.75 px stroke, round caps and joins, a distinct
// glyph per entry. Decorative (aria-hidden); the label beside it carries the meaning. Paths are original geometry, not a vendor set.
import type { SVGProps } from "react";

const P = {
  dashboard: "M4 4h6v7H4z M14 4h6v4h-6z M14 12h6v8h-6z M4 15h6v5H4z",
  users: "M16 20v-1a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v1 M10 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6 M20 20v-1a3 3 0 0 0-2-2.8 M16 5.2a3 3 0 0 1 0 5.6",
  store: "M4 9l1-5h14l1 5 M4 9v11h16V9 M4 9a3 3 0 0 0 5 0 3 3 0 0 0 6 0 3 3 0 0 0 5 0 M10 20v-5h4v5",
  box: "M12 3l8 4.5v9L12 21l-8-4.5v-9z M4 7.5l8 4.5 8-4.5 M12 12v9",
  route: "M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4 M18 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4 M8 17h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6",
  chart: "M4 20V10 M10 20V4 M16 20v-7 M22 20H2",
  trophy: "M8 4h8v5a4 4 0 0 1-8 0z M8 6H5a3 3 0 0 0 3 4 M16 6h3a3 3 0 0 1-3 4 M12 13v4 M9 20h6 M10 17h4",
  key: "M7 14a4 4 0 1 1 4-4 M11 12l9 9 M17 18l2-2 M14 15l2-2",
  cap: "M2 9l10-5 10 5-10 5z M6 11.5V16c0 1.5 3 3 6 3s6-1.5 6-3v-4.5 M22 9v6",
  trend: "M3 17l6-6 4 4 8-8 M15 7h6v6",
  check: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18 M8 12l3 3 5-6",
  sync: "M20 11a8 8 0 0 0-14-4L4 9 M4 4v5h5 M4 13a8 8 0 0 0 14 4l2-2 M20 20v-5h-5",
  flag: "M5 21V4 M5 4h12l-2 4 2 4H5",
  calendar: "M4 6h16v14H4z M4 10h16 M8 3v4 M16 3v4",
  file: "M7 3h8l4 4v14H7z M15 3v4h4 M10 12h6 M10 16h6",
  pin: "M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11 M12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5",
  shield: "M12 3l8 3v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z",
  gear: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6 M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.4.9a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.5a7 7 0 0 0-2 1.2l-2.4-.9-2 3.4 2 1.6a7 7 0 0 0 0 2.4l-2 1.6 2 3.4 2.4-.9a7 7 0 0 0 2 1.2L10 21h4l.5-2.5a7 7 0 0 0 2-1.2l2.4.9 2-3.4-2-1.6c.1-.4.1-.8.1-1.2",
  clipboard: "M9 4h6v3H9z M7 5H5v16h14V5h-2 M9 12h6 M9 16h6",
  receipt: "M6 3h12v18l-3-2-3 2-3-2-3 2z M9 8h6 M9 12h6",
  gauge: "M4 18a8 8 0 1 1 16 0 M12 18l4-6",
  edit: "M4 20h4L19 9l-4-4L4 16z M14 6l4 4",
  target: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18 M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M12 12h.01",
  swap: "M7 4L3 8l4 4 M3 8h14 M17 20l4-4-4-4 M21 16H7",
  wifi: "M5 12a10 10 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 19h.01",
  gift: "M4 9h16v4H4z M6 13v8h12v-8 M12 9v12 M12 9S9 9 8.5 7 11 4 12 9 M12 9s3 0 3.5-2S13 4 12 9",
  table: "M4 5h16v14H4z M4 10h16 M4 15h16 M10 5v14",
  alert: "M12 3l10 18H2z M12 10v5 M12 18h.01",
  layers: "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5",
  phone: "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2",
  shieldCheck: "M12 3l8 3v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z M8.5 12l2.5 2.5 4.5-5",
  hash: "M5 9h14 M5 15h14 M10 4L8 20 M16 4l-2 16",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18 M12 7v5l3 2",
  device: "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M11 18h2",
  lock: "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3",
  toggle: "M8 7h8a5 5 0 0 1 0 10H8A5 5 0 0 1 8 7z M8 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4",
  printer: "M7 8V4h10v4 M7 17H4v-7h16v7h-3 M7 14h10v6H7z",
  coin: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18 M14.5 9.5c-.5-1-1.5-1.5-2.5-1.5-1.4 0-2.5.8-2.5 2s1 1.7 2.5 2 2.5.8 2.5 2-1.1 2-2.5 2c-1 0-2-.5-2.5-1.5 M12 6.5V8 M12 16v1.5",
  history: "M3 12a9 9 0 1 0 3-6.7L3 8 M3 3v5h5 M12 8v5l3 2",
  qr: "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 14h2v6h-4 M14 18v2",
  tag: "M3 12V4h8l10 10-8 8z M7.5 8.5h.01",
  database: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6",
  upload: "M12 16V4 M7 9l5-5 5 5 M4 20h16",
  list: "M9 6h11 M9 12h11 M9 18h11 M4 6h.01 M4 12h.01 M4 18h.01",
  radius: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18 M12 12l6-6 M12 12h.01",
  inbox: "M4 13l2.5-8h11l2.5 8 M4 13v6h16v-6 M4 13h4.5a3.5 3.5 0 0 0 7 0H20", // tso-manual X3: the support desk's Tickets entry
  dot: "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2",
} as const;

export type NavIconName = keyof typeof P;

/** Menu entry id -> glyph. Report entries use their slug (`report-<slug>`). Unknown ids fall back to the generic file icon. */
const BY_ID: Record<string, NavIconName> = {
  dashboard: "dashboard", "daily-tracking": "trend", "tso-daily-tracking": "users", "final-submit": "check", "sync-health": "sync", exceptions: "flag", "flagged-memos": "flag", leave: "calendar",
  routes: "route", tutorial: "cap", credentials: "key", "products-tree": "layers", "products-category": "layers", "products-segment": "list", "products-brand": "tag", "products-variant": "box",
  "report-retailers": "store", "report-skus": "box", "report-task-planner": "clipboard", "report-geo-capture": "pin", "report-std-memo": "receipt", "report-sr-efficiency": "gauge",
  "report-route-std": "route", "report-data-entry-log": "edit", "report-final-submit-log": "check", "report-cpr-bsr": "target", "report-by-outlet": "store", "report-gigo": "swap",
  "report-by-outlet-by-day": "calendar", "report-online-offline": "wifi", "report-free-sample": "gift", "report-tso-top-sheet": "table", "report-sr-outlets": "users", "report-leaderboard": "trophy",
  "report-suspicious-location": "alert", "report-ds-rrs": "trend", "report-amo-call": "phone", "report-dss": "layers", "report-route-memo": "receipt", "report-qc": "shieldCheck",
  "report-route-qc": "check", "report-memo-number-gaps": "hash", "report-geofence-calibration": "radius",
  "tso-final-submit": "check", "tso-web-entry": "edit", "tso-qc-entry": "shieldCheck", "tso-wh-qc": "box", "tso-device-otp": "device",
  "admin-home": "shield", "admin-master-data": "database", "admin-outlet-requests": "store", "admin-outlet-change": "edit", "admin-team": "users", "admin-radius": "radius", "admin-wholesale": "box", "admin-audit": "history",
  "admin-tickets": "inbox", // tso-manual X3 (R-W-13 (1)): the sidebar draws this map, so the ruled MENU_ICON line alone would leave the dot
  "cfg-home": "gear", "cfg-keys": "key", "cfg-rules": "list", "cfg-switches": "toggle", "cfg-changes": "edit", "cfg-history": "history", "cfg-reach": "pin", "cfg-devices": "device", "cfg-releases": "upload",
  "cfg-quarantine": "alert", "cfg-sync-health": "sync", "cfg-geofence": "radius", "cfg-enrolment": "qr", "cfg-app-block": "lock", "cfg-day": "clock", "cfg-qc-faults": "shieldCheck", "cfg-flags": "flag",
  "cfg-permissions": "shield", "cfg-entry-unlocks": "lock", "cfg-print-templates": "printer", "cfg-supervisor-targets": "target", "cfg-dues": "coin", "cfg-export-log": "file", "cfg-data-entry": "edit",
  "cfg-calendar": "calendar", "cfg-device-otps": "key", "cfg-audit": "history",
};

export const navIconFor = (id: string): NavIconName => BY_ID[id] ?? (id.startsWith("report-") ? "file" : "dot");

export function NavIcon({ name, size = 20, ...rest }: { name: NavIconName; size?: number } & Omit<SVGProps<SVGSVGElement>, "name">) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" className="shrink-0" {...rest}>
      <path d={P[name]} />
    </svg>
  );
}
