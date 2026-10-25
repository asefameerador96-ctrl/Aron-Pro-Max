// D-NIGHT-MDM-ALLOWLIST-01: the work-hours allow-list card of the MDM policy editor: validation, the body sent, the preview,
// the card in Bangla and English, and the mock round trip through the BFF (the member stays out of policies without it).
import { describe, expect, it, vi } from "vitest";
import { PolicyEditor } from "@/components/admin/mdm/policy-editor";
import { WorkHoursAllowlistCard } from "@/components/admin/mdm/work-hours-allowlist-card";
import { ALLOWLIST_DEFAULT, ALLOWLIST_MAX, allowlistBody, allowlistPreview, dhakaWeekday, validateAllowlist, type MdmWorkHoursAllowlist } from "@/lib/admin/mdm";
import { PACKAGE_PATTERN } from "@/lib/admin/packages";
import { bn } from "@/lib/i18n/messages-bn";
import { en } from "@/lib/i18n/messages-en";
import { admin, op, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();
const REASON = "Lock other apps in work hours";

const al = (over: Partial<MdmWorkHoursAllowlist> = {}): MdmWorkHoursAllowlist => ({
  enabled: true,
  packages: ["com.whatsapp", "com.hihonor.calculator", "com.android.calculator2"],
  schedule: { mode: "window", days: [7, 1, 2, 3, 4], start: "09:00", end: "18:00" },
  grace_min: 0,
  ...over,
});
// 2026-10-25 is a Sunday; 2026-10-23 a Friday (Dhaka).
const sunday = new Date("2026-10-25T04:00:00Z");
const friday = new Date("2026-10-23T04:00:00Z");

describe("allow-list rules", () => {
  it("validates like the API", () => {
    expect(validateAllowlist(al(), PACKAGE_PATTERN)).toEqual({});
    expect(validateAllowlist(al({ packages: ["not a package"] }), PACKAGE_PATTERN)).toEqual({ allowlist_packages: "invalid" });
    expect(validateAllowlist(al({ packages: ["com.whatsapp", "com.whatsapp"] }), PACKAGE_PATTERN)).toEqual({ allowlist_packages: "invalid" });
    expect(validateAllowlist(al({ packages: Array.from({ length: ALLOWLIST_MAX + 1 }, (_, i) => `com.app.a${i}`) }), PACKAGE_PATTERN)).toEqual({ allowlist_packages: "too_many" });
    expect(validateAllowlist(al({ schedule: { mode: "window", days: [1], start: "18:00", end: "09:00" } }), PACKAGE_PATTERN)).toEqual({ allowlist_hours: "invalid" });
    expect(validateAllowlist(al({ schedule: { mode: "window", days: [], start: "09:00", end: "18:00" } }), PACKAGE_PATTERN)).toEqual({ allowlist_days: "required" });
    expect(validateAllowlist(al({ grace_min: 31 }), PACKAGE_PATTERN)).toEqual({ allowlist_grace: "invalid" });
    expect(validateAllowlist(al({ grace_min: 20, schedule: { mode: "calendar", start: "09:00", end: "09:20" } }), PACKAGE_PATTERN)).toEqual({ allowlist_grace: "invalid" });
  });
  it("sends days only in window mode, sorted and once", () => {
    expect(allowlistBody(al({ schedule: { mode: "window", days: [4, 1, 1], start: "09:00", end: "18:00" } })).schedule.days).toEqual([1, 4]);
    expect(allowlistBody(al({ schedule: { mode: "calendar", days: [1, 2], start: "09:00", end: "18:00" } })).schedule.days).toEqual([]);
    expect(allowlistBody({ ...ALLOWLIST_DEFAULT }).grace_min).toBe(5);
  });
  it("previews today in Dhaka with the grace", () => {
    expect(dhakaWeekday(new Date("2026-10-24T20:00:00Z"))).toBe(7); // Saturday 20:00Z is Sunday 02:00 Dhaka
    expect(allowlistPreview(al(), sunday)).toEqual({ kind: "blocked", from: "09:00", to: "18:00", n: 3, calendar: false });
    expect(allowlistPreview(al({ grace_min: 5 }), sunday)).toMatchObject({ from: "09:05" });
    expect(allowlistPreview(al(), friday)).toEqual({ kind: "free_today" });
    expect(allowlistPreview(al({ schedule: { mode: "calendar", start: "09:00", end: "18:00" } }), friday)).toMatchObject({ kind: "blocked", calendar: true });
    expect(allowlistPreview(al({ enabled: false }), sunday)).toEqual({ kind: "off" });
    expect(allowlistPreview(al({ schedule: { mode: "window", days: [7], start: "18:00", end: "09:00" } }), sunday)).toEqual({ kind: "invalid" });
  });
  it("every allow-list string exists in Bangla and English", () => {
    const keys = Object.keys(en).filter((k) => k.startsWith("mdm.al."));
    expect(keys.length).toBeGreaterThan(20);
    for (const k of keys) expect(bn[k as keyof typeof bn], k).toBeTruthy();
  });
});

describe("allow-list card", () => {
  it("shows the list, presets, schedule and the preview line", () => {
    const m = html(<WorkHoursAllowlistCard value={al()} onChange={() => {}} readOnly={false} errors={{}} now={sunday} />);
    expect(m).toContain('data-testid="mdm-allowlist"');
    expect(text(m)).toContain("Blocked today 09:00–18:00 except 3 apps");
    expect(m).toContain("mdm-al-preset-com.whatsapp.w4b");
    expect(m).not.toContain("mdm-al-preset-com.whatsapp\""); // already on the list
    expect(m).toContain('aria-pressed="true" data-testid="mdm-al-day-7"');
    expect(m).toContain('aria-pressed="false" data-testid="mdm-al-day-5"');
  });
  it("is Bangla-first and read-only for a reader", () => {
    const m = html(<WorkHoursAllowlistCard value={al()} onChange={() => {}} readOnly errors={{}} now={sunday} />, "bn");
    expect(text(m)).toContain("কাজের সময়ে অনুমোদিত অ্যাপ");
    expect(m).not.toContain("mdm-al-preset-");
    expect(m).not.toContain('data-testid="mdm-al-add"');
  });
  it("sits in the policy editor and opens with the stored allow-list", () => {
    const initial = { policy_id: 9, name: "Work hours", description: null, allowed_packages: [], block_windows: [], kiosk: { enabled: false }, location_sampling: { mode: "on_demand" as const }, version: 1, device_count: 0, updated_at: "2026-10-24T00:00:00Z", work_hours_allowlist: al() };
    const m = html(<PolicyEditor initial={initial} canWrite />);
    expect(m).toContain('data-testid="mdm-allowlist"');
    expect(m).toContain("com.hihonor.calculator");
    expect(m).toMatch(/data-testid="mdm-al-on"[^>]*checked|checked=""[^>]*data-testid="mdm-al-on"/);
  });
});

describe("allow-list through the BFF and the mock", () => {
  const write = { name: "Work hours", description: null, allowed_packages: [], block_windows: [], kiosk: { enabled: false }, location_sampling: { mode: "on_demand" } };
  it("is stored with the policy, sorted, and absent on policies without one", async () => {
    const c = await admin();
    const res = await op(c, { op: "mdm.policy.create", body: { ...write, work_hours_allowlist: allowlistBody(al()) }, reason: REASON });
    expect(res.status).toBe(201);
    const p = ((await res.json()) as { data: { work_hours_allowlist: MdmWorkHoursAllowlist } }).data;
    expect(p.work_hours_allowlist.packages).toEqual(["com.android.calculator2", "com.hihonor.calculator", "com.whatsapp"]);
    expect(h.mock.state.mdm.policies.find((x) => x.policy_id === 1)).not.toHaveProperty("work_hours_allowlist");
  });
  it("refuses an invalid allow-list with its field", async () => {
    const c = await admin();
    const res = await op(c, { op: "mdm.policy.create", body: { ...write, name: "Bad hours", work_hours_allowlist: allowlistBody(al({ schedule: { mode: "window", days: [1], start: "18:00", end: "09:00" } })) }, reason: REASON });
    expect(res.status).toBe(400);
    expect(JSON.stringify(await res.json())).toContain("/work_hours_allowlist/schedule/end");
  });
});
