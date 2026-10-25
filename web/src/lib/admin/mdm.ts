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

// ---------- work-hours allow-list (D-NIGHT-MDM-ALLOWLIST-01) ----------
// The member is queued for contract 1.9 (row 6, docs/requests/contract-v1.9-shapes-mdm.md); until the generated types carry
// it, these local types mirror that shape exactly. The API leaves the member out when a policy has none.
export type MdmAllowlistSchedule = { mode: "calendar" | "window"; days?: number[]; start: string; end: string };
export type MdmWorkHoursAllowlist = { enabled: boolean; packages: string[]; schedule: MdmAllowlistSchedule; grace_min?: number };
export type MdmPolicyFull = MdmPolicy & { work_hours_allowlist?: MdmWorkHoursAllowlist };

export const ALLOWLIST_MAX = 50;
export const ALLOWLIST_MAX_GRACE = 30;
export const ALLOWLIST_DEFAULT: MdmWorkHoursAllowlist = { enabled: false, packages: [], schedule: { mode: "calendar", days: [], start: "09:00", end: "18:00" }, grace_min: 5 };

/** One-tap presets for the allow-list until the packages catalogue (contract 1.9) lists the phone's own apps. */
export const ALLOWLIST_PRESETS: readonly { pkg: string; label: string }[] = [
  { pkg: "com.whatsapp", label: "WhatsApp" },
  { pkg: "com.whatsapp.w4b", label: "WhatsApp Business" },
  { pkg: "com.android.calculator2", label: "Calculator" },
  { pkg: "com.hihonor.calculator", label: "Calculator (Honor)" },
  { pkg: "com.huawei.calculator", label: "Calculator (Huawei)" },
  { pkg: "com.google.android.calculator", label: "Calculator (Google)" },
  { pkg: "com.sec.android.app.popupcalculator", label: "Calculator (Samsung)" },
];

const minutesOf = (s: string): number | null => {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(s);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};
const clock = (min: number) => `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;

/** Client-side check of the allow-list, same rules as the API (field -> error code). */
export function validateAllowlist(a: MdmWorkHoursAllowlist, packagePattern: RegExp): Record<string, string> {
  const e: Record<string, string> = {};
  if (a.packages.length > ALLOWLIST_MAX) e.allowlist_packages = "too_many";
  else if (a.packages.some((p) => !packagePattern.test(p)) || new Set(a.packages).size !== a.packages.length) e.allowlist_packages = "invalid";
  const s = minutesOf(a.schedule.start);
  const t = minutesOf(a.schedule.end);
  if (s === null || t === null || t <= s) e.allowlist_hours = "invalid";
  if (a.schedule.mode === "window" && !(a.schedule.days ?? []).length) e.allowlist_days = "required";
  const g = a.grace_min ?? 5;
  if (!Number.isInteger(g) || g < 0 || g > ALLOWLIST_MAX_GRACE || (s !== null && t !== null && t > s && g >= t - s)) e.allowlist_grace = "invalid";
  return e;
}

/** The body member the API expects: days only in `window` mode, sorted; packages as entered. */
export function allowlistBody(a: MdmWorkHoursAllowlist): MdmWorkHoursAllowlist {
  const days = a.schedule.mode === "window" ? [...new Set(a.schedule.days ?? [])].sort((x, y) => x - y) : [];
  return { enabled: a.enabled, packages: a.packages, schedule: { mode: a.schedule.mode, days, start: a.schedule.start, end: a.schedule.end }, grace_min: a.grace_min ?? 5 };
}

/** ISO weekday (1 = Monday) of an instant in Dhaka (UTC+6, no DST). */
export const dhakaWeekday = (now: Date): number => {
  const d = new Date(now.getTime() + 6 * 3_600_000).getUTCDay(); // 0 = Sunday
  return d === 0 ? 7 : d;
};

export type AllowlistPreview =
  | { kind: "off" }
  | { kind: "invalid" }
  | { kind: "free_today" }
  | { kind: "blocked"; from: string; to: string; n: number; calendar: boolean };

/** What the phone does today (Dhaka): the blocking span after the grace, or why nothing is blocked. In calendar mode the phone also skips holidays. */
export function allowlistPreview(a: MdmWorkHoursAllowlist, now: Date): AllowlistPreview {
  if (!a.enabled) return { kind: "off" };
  const s = minutesOf(a.schedule.start);
  const t = minutesOf(a.schedule.end);
  const from = s === null ? null : s + Math.min(Math.max(a.grace_min ?? 5, 0), ALLOWLIST_MAX_GRACE);
  if (from === null || t === null || from >= t) return { kind: "invalid" };
  if (a.schedule.mode === "window" && !(a.schedule.days ?? []).includes(dhakaWeekday(now))) return { kind: "free_today" };
  return { kind: "blocked", from: clock(from), to: clock(t), n: a.packages.length, calendar: a.schedule.mode === "calendar" };
}
