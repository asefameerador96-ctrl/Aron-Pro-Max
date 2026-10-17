import { describe, expect, it } from "vitest";
import { formatLakhCrore, mtkToTaka, pctChange } from "@/lib/dash/lakh";

const en = { lakh: "Lakh", crore: "Cr" };
const bn = { lakh: "লাখ", crore: "কোটি" };

describe("lakh and crore display (dashboard cards)", () => {
  it("scales at one lakh and one crore with two decimals", () => {
    expect(formatLakhCrore("en", 36_100_000, en)).toBe("3.61 Cr");
    expect(formatLakhCrore("en", 6_507_000, en)).toBe("65.07 Lakh");
    expect(formatLakhCrore("en", 28_067, en)).toBe("28,067");
    expect(formatLakhCrore("en", 99_999, en)).toBe("99,999");
  });
  it("uses Bengali digits and words in bn", () => {
    expect(formatLakhCrore("bn", 36_100_000, bn)).toBe("৩.৬১ কোটি");
    expect(formatLakhCrore("bn", 28_067, bn)).toBe("২৮,০৬৭");
  });
  it("converts milli-taka to taka half up and refuses fake comparisons", () => {
    expect(mtkToTaka(1_499)).toBe(1);
    expect(mtkToTaka(1_500)).toBe(2);
    expect(pctChange(110, 100)).toBeCloseTo(10);
    expect(pctChange(5, 0)).toBeNull();
    expect(pctChange(5, null)).toBeNull();
  });
});
