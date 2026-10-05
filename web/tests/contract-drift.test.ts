// The generated client must match contract/openapi.yaml, and a contract rename must break the build (N-010).
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";
import ts from "typescript";
import { describe, expect, it } from "vitest";

const CONTRACT = resolve(__dirname, "../../contract/openapi.yaml");
const COMMITTED = resolve(__dirname, "../src/contract/openapi.d.ts");

async function generate(yaml: string): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "aron-contract-"));
  const file = join(dir, "openapi.yaml");
  writeFileSync(file, yaml);
  return astToString(await openapiTS(pathToFileURL(file)));
}

const body = (generated: string) => generated.slice(generated.indexOf("export interface paths")).trim();

describe("generated API client", () => {
  const yaml = readFileSync(CONTRACT, "utf8");
  const committed = body(readFileSync(COMMITTED, "utf8"));

  it("equals what `npm run gen:contract` produces from contract/openapi.yaml (drift test)", async () => {
    const fresh = body(await generate(yaml));
    expect(fresh === committed, "src/contract/openapi.d.ts is stale: run `npm run gen:contract` and commit the result").toBe(true);
  });

  it("detects a deliberate field rename in the contract", async () => {
    const renamed = yaml.replaceAll("cluster_type:", "cluster_kind:");
    expect(renamed).not.toBe(yaml);
    const fresh = body(await generate(renamed));
    expect(fresh === committed).toBe(false);
  });

  it("a renamed field breaks type checking of code that uses it", async () => {
    const probe = `import type { components } from "./openapi";\nexport const n: components["schemas"]["ClusterWrite"]["cluster_type"] = null;\n`;
    const check = (declarations: string): string[] => {
      const dir = mkdtempSync(join(tmpdir(), "aron-probe-"));
      writeFileSync(join(dir, "openapi.d.ts"), declarations);
      writeFileSync(join(dir, "probe.ts"), probe);
      const program = ts.createProgram([join(dir, "probe.ts")], { strict: true, noEmit: true, skipLibCheck: true, module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler, target: ts.ScriptTarget.ES2022 });
      return ts.getPreEmitDiagnostics(program).map((d) => ts.flattenDiagnosticMessageText(d.messageText, "\n"));
    };
    expect(check(readFileSync(COMMITTED, "utf8"))).toEqual([]);
    const broken = check(await generate(yaml.replaceAll("cluster_type:", "cluster_kind:")));
    expect(broken.length).toBeGreaterThan(0);
    expect(broken.join("\n")).toContain("cluster_type");
  });
});
