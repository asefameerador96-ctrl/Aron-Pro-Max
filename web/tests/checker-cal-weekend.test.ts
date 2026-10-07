import { describe, expect, it } from "vitest";
import { parseConfigInput } from "@/lib/admin/config";

// Registry seed (V0009): cfg.calendar.weekend_days bounds = {"max_items": 3}; value = ISO weekdays 1..7 (docs/16 s2432).
const bounds = { max_items: 3 };
describe("checker: weekend_days value", () => {
  for (const raw of ["0", "9", "fri", "5,5", "1.5", "-1"]) {
    it(`refuses ${raw}`, () => {
      expect(parseConfigInput("list", raw, bounds, "cfg.calendar.weekend_days").ok).toBe(false);
    });
  }
});
