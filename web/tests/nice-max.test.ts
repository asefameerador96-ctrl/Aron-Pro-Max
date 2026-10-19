// The brand chart's y axis rounds its top up to a readable number (1, 2, 2.5, 5 times a power of ten).
import { describe, expect, it } from "vitest";
import { niceMax } from "@/components/dash/charts";

describe("niceMax", () => {
  it("rounds up to 1, 2, 2.5, 5 or 10 times a power of ten", () => {
    expect(niceMax(326_000)).toBe(500_000);
    expect(niceMax(217_935)).toBe(250_000);
    expect(niceMax(190_000)).toBe(200_000);
    expect(niceMax(100_000)).toBe(100_000);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(0.3)).toBe(0.5);
  });
  it("falls back to 1 for zero or a negative value", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(-5)).toBe(1);
  });
});
