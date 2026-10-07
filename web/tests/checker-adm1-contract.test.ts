// Checker ADM-1: the entity metadata (what the UI offers and sends) held against contract/openapi.yaml.
// Member-by-member comparison of every Write/Patch schema found no other drift (fields, enums, lengths, patterns,
// nullability, create-only/update-only); this file keeps only the checks that fail.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ENTITIES } from "@/app/admin/_entities/registry";

type Schema = { $ref?: string; enum?: unknown[]; name?: string; schema?: Schema };
const nodeRequire = createRequire(import.meta.url);
const yaml = nodeRequire("js-yaml") as { load: (s: string) => { components: { schemas: Record<string, Schema>; parameters: Record<string, Schema> }; paths: Record<string, { get?: { parameters?: Schema[] } }> } };
const doc = yaml.load(readFileSync(resolve(__dirname, "../../contract/openapi.yaml"), "utf8"));

function deref(s: Schema | undefined): Schema {
  let cur = s ?? {};
  while (cur.$ref) cur = doc.components.schemas[cur.$ref.split("/").pop()!] ?? {};
  return cur;
}

function listParamEnums(path: string): Record<string, unknown[]> {
  const out: Record<string, unknown[]> = {};
  for (const p of doc.paths[path]?.get?.parameters ?? []) {
    const r = p.$ref ? doc.components.parameters[p.$ref.split("/").pop()!]! : p;
    out[String(r.name)] = deref(r.schema).enum ?? [];
  }
  return out;
}

describe("list filters against the contract's query parameters", () => {
  it("CC1: every enum filter option is a value the contract's query parameter accepts (users: status=disabled is not an ActiveStatus)", () => {
    const bad: string[] = [];
    for (const meta of ENTITIES) {
      const params = listParamEnums(String(meta.api.collection));
      for (const f of meta.filters) {
        if (f.kind !== "enum") continue;
        const allowed = (params[f.param] ?? []) as string[];
        for (const o of f.options ?? []) if (!allowed.includes(o)) bad.push(`${meta.slug}?${f.param}=${o} (contract allows ${allowed.join("|")})`);
      }
    }
    expect(bad).toEqual([]);
  });
});
