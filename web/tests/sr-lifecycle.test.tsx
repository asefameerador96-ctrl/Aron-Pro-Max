import { describe, expect, it } from "vitest";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { MASTER_LINKS } from "@/app/admin/master-links";

describe("SR lifecycle wizard (F-ADM-076)", () => {
  it("is on the master-data hub and every step string exists in Bangla and English", () => {
    expect(MASTER_LINKS.some((l) => l.href === "/admin/sr-lifecycle")).toBe(true);
    for (const k of Object.keys(en).filter((x) => x.startsWith("lifecycle."))) expect(bn[k as keyof typeof bn]).toBeTruthy();
    expect(Object.keys(en).filter((x) => x.startsWith("lifecycle.")).length).toBe(12);
  });
});
