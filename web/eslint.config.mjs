import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";
import local from "./eslint-rules/no-hardcoded-text.mjs";

const config = [
  { ignores: [".next/**", "node_modules/**", "coverage/**", "playwright-report/**", "test-results/**", "src/contract/openapi.d.ts", "next-env.d.ts"] },
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    // Every UI file must be free of hard-coded text; this fails `npm run lint` and therefore `npm run build` (prebuild).
    files: ["src/app/**/*.{ts,tsx}", "src/components/**/*.{ts,tsx}"],
    plugins: { local },
    rules: { "local/no-hardcoded-text": "error" },
  },
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/consistent-type-imports": "error",
    },
  },
];

export default config;
