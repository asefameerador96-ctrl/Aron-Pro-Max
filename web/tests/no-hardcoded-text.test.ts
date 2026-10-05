// The build fails on hard-coded user-visible text (eslint rule local/no-hardcoded-text, run by `npm run lint` and `prebuild`).
import { Linter } from "eslint";
import tsParser from "@typescript-eslint/parser";
import { describe, expect, it } from "vitest";
import plugin from "../eslint-rules/no-hardcoded-text.mjs";

const linter = new Linter();
const run = (code: string) =>
  linter.verify(code, [{ files: ["**/*.tsx"], plugins: { local: plugin }, languageOptions: { parser: tsParser, parserOptions: { ecmaFeatures: { jsx: true } } }, rules: { "local/no-hardcoded-text": "error" } }], "x.tsx");

describe("local/no-hardcoded-text", () => {
  it.each([
    ["<p>Hello</p>"],
    ["<p>সাইন ইন</p>"],
    ['<p>{"Sign in"}</p>'],
    ["<p>{`Sign in`}</p>"],
    ['<input placeholder="Search" />'],
    ['<img alt="logo" src="/x.png" />'],
    ['<button aria-label="Close">x</button>'],
    ['<input title={"Name"} />'],
  ])("rejects %s", (code) => {
    expect(run(code).some((m) => m.ruleId === "local/no-hardcoded-text"), code).toBe(true);
  });

  it.each([
    ["<p>{t('auth.title')}</p>"],
    ["<p>{name}</p>"],
    ["<p>—</p>"],
    ["<p>12:30 · 5/10</p>"],
    ["<b>Aron</b>"],
    ['<a href="/login" className="x" data-testid="y" />'],
    ['<input aria-label={t("auth.username")} />'],
    ["<p>{count} / {total}</p>"],
  ])("accepts %s", (code) => {
    expect(run(code)).toEqual([]);
  });
});
