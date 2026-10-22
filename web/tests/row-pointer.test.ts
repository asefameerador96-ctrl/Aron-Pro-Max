import { describe, expect, it } from "vitest";
import { rowIssuesOf, TARGET_MEMBER_COLUMN } from "@/lib/admin/row-pointer";

describe("rowIssuesOf", () => {
  it("turns 0-based row pointers into 1-based rows and maps members to columns", () => {
    const out = rowIssuesOf([{ pointer: "body.rows/0/route_code", code: "unknown_route" }, { pointer: "body.rows/12/qty_base", code: "invalid_quantity" }], (m) => TARGET_MEMBER_COLUMN[m] ?? m);
    expect(out).toEqual([{ row: 1, column: "route", code: "unknown_route" }, { row: 13, column: "quantity", code: "invalid_quantity" }]);
  });
  it("accepts a leading slash and a row without member, ignores other pointers and junk", () => {
    expect(rowIssuesOf([{ pointer: "/rows/3", code: "duplicate_row" }, { pointer: "/change_reason", code: "too_short" }, null, { pointer: 5, code: "x" }])).toEqual([{ row: 4, column: "sheet", code: "duplicate_row" }]);
    expect(rowIssuesOf(undefined)).toEqual([]);
  });
});
