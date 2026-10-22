// REQUEST: docs/requests/backend-admin-sku-pack-edit.md. Passes while SkuPatch lacks the pack members, turns RED when it gains them:
// then make base_per_pack and entry_unit_default `rw` in the SKU entity and delete this file.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const slice = readFileSync(resolve(__dirname, "../../../contract/slices/schemas/SkuPatch.yaml"), "utf8");

describe("SkuPatch carries the pack size (not yet: backend-admin)", () => {
  it.fails("SkuPatch has base_per_pack and entry_unit_default", () => {
    expect(slice).toContain("base_per_pack:");
    expect(slice).toContain("entry_unit_default:");
  });
});
