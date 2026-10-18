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
    expect(Object.is(lakhMtkToTaka(0), 0)).toBe(true); // no negative zero
  });
});

// F-SYS-051: Western three-digit grouping everywhere, digits in the UI language; hand-written expectations.
import { formatTaka, formatTakaDisplay } from "@/lib/reports/format";
import { formatLakhCrore } from "@/lib/dash/lakh";

describe("one grouping for every full amount (F-SYS-051)", () => {
  it("groups in threes in both languages, digits follow the language", () => {
    expect(formatTakaDisplay("bn", 91_266_905_420)).toBe("৯১,২৬৬,৯০৫.৪২");
    expect(formatTakaDisplay("en", 91_266_905_420)).toBe("91,266,905.42");
    expect(formatTaka("bn", 12_345_678_900)).toBe("১২,৩৪৫,৬৭৮.৯০০");
    expect(formatMtk("bn", 91_266_905_420)).toBe(formatTakaDisplay("bn", 91_266_905_420));
    expect(formatMtkExact("bn", 91_266_905_420)).toBe(formatTaka("bn", 91_266_905_420));
    expect(formatTakaDisplay("bn", 543_435_000)).toBe("৫৪৩,৪৩৫.০০");
  });
  it("above 2^53 in both languages", () => {
    expect(formatTaka("en", HUGE)).toBe("9,007,199,254,740.993");
    expect(formatTaka("bn", HUGE)).toBe("৯,০০৭,১৯৯,২৫৪,৭৪০.৯৯৩");
    expect(formatTakaDisplay("bn", String(HUGE))).toBe("৯,০০৭,১৯৯,২৫৪,৭৪০.৯৯");
  });
  it("compact tile figures keep lakh and crore words; very large ones group in threes", () => {
    const w = { lakh: "Lakh", crore: "Cr" };
    expect(formatLakhCrore("en", 361_000_000 * 1, w)).toBe("36.10 Cr");
    expect(formatLakhCrore("en", 6_507_000, w)).toBe("65.07 Lakh");
    expect(formatLakhCrore("en", 28_067, w)).toBe("28,067");
    expect(formatLakhCrore("bn", 123_456_780_000_000, { lakh: "লাখ", crore: "কোটি" })).toBe("১২,৩৪৫,৬৭৮.০০ কোটি");
  });
});

describe("compact lakh/crore figures by BigInt maths", () => {
  const w = { lakh: "Lakh", crore: "Cr" };
  it("rounds the hundredths half up with no float error and keeps small counts whole", () => {
    expect(formatLakhCrore("en", 100_500, w)).toBe("1.01 Lakh"); // 1.005 lakh: a double would say 1.00
    expect(formatLakhCrore("en", 10_050_000, w)).toBe("1.01 Cr");
    expect(formatLakhCrore("en", 99_999, w)).toBe("99,999");
    expect(formatLakhCrore("en", -361_000_000, w)).toBe("-36.10 Cr");
    expect(formatLakhCrore("bn", 6_507_000, { lakh: "লাখ", crore: "কোটি" })).toBe("৬৫.০৭ লাখ");
    expect(formatLakhCrore("en", Number.NaN, w)).toBe("—");
  });
  it("whole-taka rounding stays exact for large values", () => {
    expect(lakhMtkToTaka(9_007_199_254_740_000)).toBe(9_007_199_254_740);
    expect(lakhMtkToTaka(Number.NaN)).toBeNaN();
  });
});

describe("non-integer and unsafe compact input is not shown", () => {
  it("formatLakhCrore answers an em dash and mtkToTaka NaN, never another amount", () => {
    const w = { lakh: "Lakh", crore: "Cr" };
    for (const bad of [1.5, 2 ** 60, Number.POSITIVE_INFINITY, Number.NaN]) {
      expect(formatLakhCrore("en", bad, w), String(bad)).toBe("—");
      expect(lakhMtkToTaka(bad), String(bad)).toBeNaN();
    }
    expect(formatLakhCrore("en", lakhMtkToTaka(2 ** 60), w)).toBe("—");
  });
});
