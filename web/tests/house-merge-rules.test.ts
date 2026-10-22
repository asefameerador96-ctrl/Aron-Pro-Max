import { describe, expect, it } from "vitest";
import { opRules } from "@/lib/admin/op-rules";

describe("house.merge rules", () => {
  it("accepts a keeper and other houses", () => expect(opRules("house.merge", { keep_id: 10, merge_ids: [20], change_reason: "same depot twice" })).toEqual([]));
  it("refuses the keeper among the merged, duplicates, none and unknown members", () => {
    expect(opRules("house.merge", { keep_id: 10, merge_ids: [10] })).toEqual([{ pointer: "/merge_ids", code: "not_duplicates" }]);
    expect(opRules("house.merge", { keep_id: 10, merge_ids: [20, 20] })).toEqual([{ pointer: "/merge_ids", code: "invalid" }]);
    expect(opRules("house.merge", { keep_id: 10, merge_ids: [] })).toEqual([{ pointer: "/merge_ids", code: "invalid" }]);
    expect(opRules("house.merge", { keep_id: 0, merge_ids: [1], x: 1 }).map((p) => p.pointer)).toEqual(["/keep_id", "/x"]);
  });
});
