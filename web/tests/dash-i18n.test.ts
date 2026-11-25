// Completion pass item 4: the dashboard catalogue is complete in both languages and the Bangla text has no Latin digits.
import { describe, expect, it } from "vitest";
import { dashBn } from "@/lib/i18n/messages-dash-bn";
import { dashEn } from "@/lib/i18n/messages-dash-en";
import { REFRESH_MS } from "@/components/dash/table-actions";

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const BENGALI = /[ঀ-৿]/;
/** Bangla values that carry a technical token with Latin digits on purpose: screen codes (P13), hash names, version and memo-number examples. */
const LATIN_DIGIT_OK = /^(de\.memo_no\.hint|day\.p15\.title|cfgp14\.title|sh\.title|sh\.col\.p95|rel\.field\.(version_name|sha256|signing)|cfgp\.audit\.title)$/;

describe("dashboard catalogue", () => {
  it("bn and en have the same keys, no key missing either way", () => {
    expect(Object.keys(dashBn).sort()).toEqual(Object.keys(dashEn).sort());
  });
  it("every bn value is non-empty, Bangla script, with the same placeholders as en", () => {
    for (const [k, v] of Object.entries(dashBn)) {
      expect(v.trim(), k).not.toBe("");
      expect(placeholders(v), k).toEqual(placeholders(dashEn[k as keyof typeof dashEn]));
      if (!/^(app\.|common\.language)/.test(k)) expect(BENGALI.test(v), `${k}: ${v}`).toBe(true);
    }
  });
  it("the auto-refresh note states the interval the page really uses (REFRESH_MS, D-NIGHT-SYNC-01)", () => {
    const seconds = REFRESH_MS / 1000;
    expect(dashEn["dashboard.auto_refresh"]).toContain(`every ${seconds} seconds`);
    const bn = String(seconds).replace(/\d/g, (d) => String.fromCharCode(0x09e6 + Number(d)));   // Bengali digits
    expect(dashBn["dashboard.auto_refresh"]).toContain(`প্রতি ${bn} সেকেন্ডে`);
  });
  it("no Latin digit in a bn value outside the listed technical tokens", () => {
    const bad = Object.entries(dashBn).filter(([k, v]) => !LATIN_DIGIT_OK.test(k) && /\d/.test(v.replace(/\{\w+\}/g, "")));
    expect(bad).toEqual([]);
  });
});
