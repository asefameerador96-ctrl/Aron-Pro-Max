// CHECKER N-010: failing tests for confirmed defects. Do not weaken; fix the product code.
import { Linter } from "eslint";
import tsParser from "@typescript-eslint/parser";
import { describe, expect, it } from "vitest";
import { safeNext } from "@/lib/api/origin";
import plugin from "../eslint-rules/no-hardcoded-text.mjs";

describe("D1: safeNext must not allow an open redirect", () => {
  // WHATWG URL parsing strips TAB/LF/CR, so "/\t/evil.com" resolves to "//evil.com" (host evil.com).
  it.each(["/\t/evil.com", "/\n/evil.com", "/\r/evil.com", "/%09/evil.com"])("%j stays on the site", (raw) => {
    const target = new URL(safeNext(raw), "https://aron.example");
    expect(target.origin).toBe("https://aron.example");
  });
  it("decoded query value from /api/bff/locale?next=/%09/evil.com", () => {
    const decoded = new URL("http://x/api/bff/locale?next=/%09/evil.com").searchParams.get("next");
    expect(new URL(safeNext(decoded), "https://aron.example").origin).toBe("https://aron.example");
  });
});

describe("D2: local/no-hardcoded-text misses obvious user-visible literals", () => {
  const linter = new Linter();
  const run = (code: string) =>
    linter.verify(code, [{ files: ["**/*.tsx"], plugins: { local: plugin }, languageOptions: { parser: tsParser, parserOptions: { ecmaFeatures: { jsx: true } } }, rules: { "local/no-hardcoded-text": "error" } }], "x.tsx");
  it.each([
    ['<p>{ok ? "Saved" : "Failed"}</p>'],
    ['<p>{ok && "Saved"}</p>'],
    ["<p>{`Saved ${n} rows`}</p>"],
    ['<p>{"Saved " + n}</p>'],
    ["<button aria-label={`Close ${n}`} />"],
    ['<input placeholder={ok ? "Search" : "Find"} />'],
    ['<input placeholder={"Search " + n} />'],
  ])("rejects %s", (code) => {
    expect(run(code).some((m) => m.ruleId === "local/no-hardcoded-text"), code).toBe(true);
  });
});
