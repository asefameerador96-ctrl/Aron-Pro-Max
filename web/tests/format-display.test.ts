// Card and hero money (docs/24 s7, tokens.md s7.1, web-glass s4): two decimals from integer milli-taka, half up, no floats;
// report cells keep the three decimals of formatTaka (docs/24 s12.3), which is asserted untouched here.
import { describe, expect, it } from "vitest";
import { formatTaka, formatTakaDisplay } from "@/lib/reports/format";

describe("formatTakaDisplay", () => {
  it("prints two decimals with locale grouping and Bengali digits for bn", () => {
    expect(formatTakaDisplay("en", 543_435_000)).toBe("543,435.00");
    expect(formatTakaDisplay("bn", 543_435_000)).toBe("৫,৪৩,৪৩৫.০০");
    expect(formatTakaDisplay("en", 4_828_500)).toBe("4,828.50");
    expect(formatTakaDisplay("en", 0)).toBe("0.00");
    expect(formatTakaDisplay("en", 7)).toBe("0.01");
  });
  it("rounds the milli digit half up on the magnitude, by integer maths", () => {
    expect(formatTakaDisplay("en", 7_935)).toBe("7.94");
    expect(formatTakaDisplay("en", 7_934)).toBe("7.93");
    expect(formatTakaDisplay("en", 1_164_195)).toBe("1,164.20");
    expect(formatTakaDisplay("en", 999_995)).toBe("1,000.00");
    expect(formatTakaDisplay("en", -7_935)).toBe("-7.94");
    expect(formatTakaDisplay("en", -4)).toBe("0.00"); // rounds to zero: no "-0.00"
    expect(formatTakaDisplay("en", "123456789012345678901")).toBe("123,456,789,012,345,678.90");
  });
  it("never shows a different amount for a malformed one", () => {
    expect(formatTakaDisplay("en", "1.5")).toBe("—");
    expect(formatTakaDisplay("en", Number.NaN)).toBe("—");
    expect(formatTakaDisplay("en", 2 ** 53)).toBe("—");
  });
  it("leaves report cells on three decimals", () => {
    expect(formatTaka("en", 7_935)).toBe("7.935");
    expect(formatTaka("en", 543_435_000)).toBe("543,435.000");
  });
});
