// MDM section (D-MDM-01, docs/34 s5): access level, wire types, compliance flag list and the schedule grid <-> block window conversion.
import type { Role, Schemas } from "@/contract/types";

export type MdmDevice = Schemas["MdmDevice"];
export type MdmDevicePage = Schemas["MdmDevicePage"];
export type MdmDeviceDetail = Schemas["MdmDeviceDetail"];
export type MdmCommand = Schemas["MdmCommand"];
export type MdmCommandPage = Schemas["MdmCommandPage"];
export type MdmPolicy = Schemas["MdmPolicy"];
export type MdmPolicyPage = Schemas["MdmPolicyPage"];
export type MdmBlockWindow = Schemas["MdmBlockWindow"];
export type MdmActivityEntry = Schemas["MdmActivityEntry"];
export type MdmActivityPage = Schemas["MdmActivityPage"];
export type MdmAlert = Schemas["MdmAlert"];
export type MdmAlertPage = Schemas["MdmAlertPage"];
export type MdmComplianceFlag = Schemas["MdmComplianceFlag"];
export type MdmCommandType = Schemas["MdmCommandType"];

export const MDM_FLAGS: readonly MdmComplianceFlag[] = ["rooted", "mock_location", "outdated_app", "developer_options", "adb_enabled", "not_device_owner", "location_off", "auto_time_off", "policy_drift", "stale_contact"];
export const MDM_COMMANDS = ["lock", "unlock", "message", "locate", "reboot", "factory_reset"] as const;
export const MDM_STATES = ["enrolled", "active", "suspended", "revoked", "replaced"] as const;

/** `r` lists and details only, `w` also actions and edits, `none` no MDM section at all. */
export type MdmLevel = "none" | "r" | "w";

/**
 * Super Admin writes; an Admin follows the `mdm` module grant when the session carries one (backend-authz, `modules` claim),
 * and writes until the claim exists. Every other role has no MDM. The API repeats the check on every call.
 */
export function mdmLevel(role: Role | undefined, modules?: Record<string, string> | null): MdmLevel {
  if (role === "SUPERADMIN") return "w";
  if (role !== "ADMIN") return "none";
  const g = modules?.mdm;
  if (g === undefined) return "w";
  return g === "w" ? "w" : g === "r" ? "r" : "none";
}

/** Same rule on the session user, reading an optional `modules` claim without widening the session type. */
export function mdmLevelOf(user: { role: Role; modules?: Record<string, string> | null }): MdmLevel {
  return mdmLevel(user.role, user.modules);
}

export const MDM_PATH_ROLES = ["ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

// ---------- schedule grid ----------
export const SLOT_MIN = 30;
export const SLOTS = (24 * 60) / SLOT_MIN; // 48
/** grid[d][s]: weekday d (0 = Monday .. 6 = Sunday) blocked in half-hour slot s (0 = 00:00). */
export type Grid = boolean[][];

export const emptyGrid = (): Grid => Array.from({ length: 7 }, () => Array<boolean>(SLOTS).fill(false));

const hhmm = (slot: number): string => {
  const m = (slot % SLOTS) * SLOT_MIN;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
};
const toSlot = (s: string): number => {
  const [h, m] = s.split(":").map(Number);
  return Math.round((h! * 60 + m!) / SLOT_MIN);
};

/** Runs of blocked slots per weekday, joined across weekdays with the identical run, as MdmBlockWindow[] (no packages = every non-allowed app). */
export function gridToWindows(grid: Grid): MdmBlockWindow[] {
  const byRun = new Map<string, { start: number; end: number; days: number[] }>();
  for (let d = 0; d < 7; d++) {
    const row = grid[d]!;
    let s = 0;
    while (s < SLOTS) {
      if (!row[s]) {
        s++;
        continue;
      }
      let e = s;
      while (e < SLOTS && row[e]) e++;
      const key = `${s}-${e}`;
      const hit = byRun.get(key);
      if (hit) hit.days.push(d + 1);
      else byRun.set(key, { start: s, end: e, days: [d + 1] });
      s = e;
    }
  }
  return [...byRun.values()].sort((a, b) => a.days[0]! - b.days[0]! || a.start - b.start).map((r) => ({ days: r.days, start: hhmm(r.start), end: hhmm(r.end) }));
}

/** Inverse for windows without a package list; a window that runs past midnight (end <= start) continues on the next weekday. */
export function windowsToGrid(windows: readonly MdmBlockWindow[]): Grid {
  const g = emptyGrid();
  for (const w of windows) {
    if (w.packages && w.packages.length) continue;
    const s = toSlot(w.start);
    const e = toSlot(w.end);
    for (const day of w.days) {
      const d = day - 1;
      if (e > s) for (let i = s; i < e; i++) g[d]![i] = true;
      else {
        for (let i = s; i < SLOTS; i++) g[d]![i] = true;
        for (let i = 0; i < e; i++) g[(d + 1) % 7]![i] = true;
      }
    }
  }
  return g;
}

/** Windows that name packages cannot be drawn on the grid; the editor keeps them as they are. */
export const packageWindows = (windows: readonly MdmBlockWindow[]): MdmBlockWindow[] => windows.filter((w) => w.packages && w.packages.length > 0);

export const MAX_WINDOWS = 14;

/** Suggested catalogue for the allowed-apps picker (the rows of app.app_package that are allowed by default). */
export const ALLOWED_SUGGESTIONS: readonly { pkg: string; label: string }[] = [
  { pkg: "com.whatsapp", label: "WhatsApp" },
  { pkg: "com.facebook.orca", label: "Messenger" },
  { pkg: "com.google.android.dialer", label: "Phone (Google)" },
  { pkg: "com.samsung.android.dialer", label: "Phone (Samsung)" },
  { pkg: "com.google.android.apps.messaging", label: "Messages (Google)" },
  { pkg: "com.samsung.android.messaging", label: "Messages (Samsung)" },
  { pkg: "com.google.android.apps.maps", label: "Google Maps" },
  { pkg: "com.android.settings", label: "Settings" },
  { pkg: "com.sec.android.app.camera", label: "Camera (Samsung)" },
  { pkg: "com.google.android.inputmethod.latin", label: "Gboard" },
  { pkg: "com.sec.android.app.launcher", label: "One UI Home" },
];

export const WEEKDAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;

/** Client-side check of a policy before it is sent (the API validates again). Returns field -> error code. */
export function validatePolicy(p: { name: string; blockWindows: readonly MdmBlockWindow[]; kioskOn: boolean; kioskPackages: readonly string[]; samplingMode: string; intervalMin: string }): Record<string, string> {
  const e: Record<string, string> = {};
  const n = Array.from(p.name.trim()).length;
  if (n < 2) e.name = "required";
  else if (n > 80) e.name = "too_long";
  if (p.blockWindows.length > MAX_WINDOWS) e.schedule = "too_many";
  if (p.kioskOn && p.kioskPackages.length === 0) e.kiosk = "required";
  if (p.samplingMode === "interval") {
    const v = Number(p.intervalMin);
    if (!Number.isInteger(v) || v < 5 || v > 120) e.interval = "invalid";
  }
  return e;
}
