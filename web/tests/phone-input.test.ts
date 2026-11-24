// Push 1b item 9: a phone input must accept the field's typed shapes (the repaired value is still 11 digits).
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { normalisePhone, PHONE_INPUT_MAX } from "@/lib/phone";

describe("phone input", () => {
  it("is long enough for +880 1711-000001 and repairs it", () => {
    const typed = "+880 1711-000001";
    expect(typed.length).toBeLessThanOrEqual(PHONE_INPUT_MAX);
    expect(normalisePhone(typed).value).toBe("01711000001");
    expect(normalisePhone("(+880) 1711 000 001").value).toBe("01711000001");
  });
  it("the outlet change form does not cut the text at 11 characters", () => {
    const src = readFileSync("src/components/admin/outlet-change-form.tsx", "utf8");
    expect(src).not.toMatch(/contact_number[^\n]*maxLength=\{11\}/);
    expect(src).toMatch(/maxLength=\{PHONE_INPUT_MAX\}/);
  });
});
