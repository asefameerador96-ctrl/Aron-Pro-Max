// QA-MNY-04: money is split into taka and paisa by BigInt maths, never by float division (docs/24 s7.1).
import { describe, expect, it } from "vitest";
import { mtkToTaka as lakhMtkToTaka } from "@/lib/dash/lakh";
import { formatMtk, formatMtkExact } from "@/lib/admin/money";
import { formatPriceMtk, mtkToTaka, parseTakaToMtk } from "@/lib/admin/price-money";

const HUGE = 9_007_199_254_740_993n; // 2^53 + 1: not representable as a double

describe("admin money", () => {
  it("stays exact above 2^53", () => {
    expect(formatMtkExact("en", HUGE)).toBe("9,007,199,254,740.993");
    expect(formatMtk("en", HUGE)).toBe("9,007,199,254,740.99");
    expect(formatMtk("en", String(HUGE + 4n))).toBe("9,007,199,254,741.00"); // .997 rounds half up into the next taka
    expect(mtkToTaka(HUGE)).toBe("9007199254740.993");
  });
  it("is unchanged on safe integers", () => {
    expect(formatMtk("en", 7_935)).toBe("7.94");
    expect(formatMtk("en", -5)).toBe("-0.01");
    expect(formatMtk("en", -4)).toBe("0.00");
    expect(formatMtkExact("bn", 1_234_567)).toBe("১,২৩৪.৫৬৭");
    expect(formatPriceMtk("bn", 12_500)).toBe("১২.৫০০");
    expect(mtkToTaka(-12_345)).toBe("-12.345");
  });
  it("refuses a number that is not an exact integer instead of showing another amount", () => {
    expect(formatMtk("en", 1.5)).toBe("—");
    expect(formatMtk("en", 2 ** 60)).toBe("—");
    expect(mtkToTaka(Number.NaN)).toBe("—");
  });
  it("parses typed taka by integer maths", () => {
    expect(parseTakaToMtk("12.5")).toBe(12_500);
    expect(parseTakaToMtk("১২.৫০০")).toBe(12_500);
    expect(parseTakaToMtk("999999999999.999")).toBe(999_999_999_999_999);
    expect(parseTakaToMtk("1.2345")).toBeNull();
  });
});

describe("dashboard whole-taka rounding", () => {
  it("rounds half up on the magnitude", () => {
    expect(lakhMtkToTaka(1_499)).toBe(1);
    expect(lakhMtkToTaka(1_500)).toBe(2);
    expect(lakhMtkToTaka(-1_500)).toBe(-2);
    expect(lakhMtkToTaka(0)).toBe(0);
  });
});
