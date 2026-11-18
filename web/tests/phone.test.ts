import { describe, expect, it } from "vitest";
import { normalisePhone } from "@/lib/phone";

describe("normalisePhone", () => {
  it("keeps a clean number untouched", () => {
    expect(normalisePhone("01711000001")).toEqual({ value: "01711000001", repaired: false, unusable: false });
  });
  it("repairs the shapes the field uses", () => {
    for (const raw of ["1711000001", "8801711000001", "+880 1711-000001", "00880 1711 000001", "(017) 11-000001", "০১৭১১০০০০০১", "۰۱۷۱۱۰۰۰۰۰۱", "٠١٧١١٠٠٠٠٠١", "88001711000001", " 01711000001 "]) {
      expect(normalisePhone(raw).value, raw).toBe("01711000001");
    }
    expect(normalisePhone("1711000001")).toMatchObject({ repaired: true, unusable: false });
    expect(normalisePhone(" 01711000001 ").repaired).toBe(false);
  });
  it("marks junk unusable", () => {
    for (const raw of ["123", "abc", "01211000001", "0171100000", "017110000012", "01711x00001", "017/11000001"]) {
      expect(normalisePhone(raw), raw).toEqual({ value: null, repaired: false, unusable: true });
    }
  });
  it("blank is no phone, not an error", () => {
    expect(normalisePhone("")).toEqual({ value: null, repaired: false, unusable: false });
    expect(normalisePhone("   ")).toEqual({ value: null, repaired: false, unusable: false });
  });
});

describe("entity phone fields repair before the pattern", () => {
  it("a phone field sends the repaired value, an unusable text stays an error", async () => {
    const { valuesSchema } = await import("@/components/admin/crud/validation");
    const { users } = await import("@/app/admin/_entities/users");
    const schema = valuesSchema(users as never, "update");
    const phone = (v: string) => schema.safeParse({ phone: v });
    const good = phone("+880 1711-000001");
    expect(good.success && (good.data as Record<string, unknown>).phone).toBe("01711000001");
    expect(phone("abc").success).toBe(false);
    expect(phone("123").success).toBe(false);
  });
});
