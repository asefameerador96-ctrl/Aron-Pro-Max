import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";
import local from "./eslint-rules/no-hardcoded-text.mjs";
import colors from "./eslint-rules/no-raw-colors.mjs";

// The Calm Glass files (web-glass s2.5 colour budget: no raw hex, slate-*, bg-white or dark: on a token colour). The list grows
// as the web lanes restyle the remaining pages; pages still on the globals.css class overrides are not listed yet.
const CALM_GLASS_FILES = [
  "src/app/layout.tsx",
  "src/app/(dashboards)/page.tsx",
  "src/app/(dashboards)/overview/page.tsx",
  "src/app/(dashboards)/loading.tsx",
  "src/app/login/**/*.{ts,tsx}",
  "src/app/admin/page.tsx",
  "src/app/admin/loading.tsx",
  "src/components/kit/**/*.{ts,tsx}",
  "src/components/{shell,sidebar,nav-link,theme-menu,connection-chip,search-field,top-bar-sentinel,scope-badge,locale-switch,logout-button,login-form,i18n-provider,brand-mark}.tsx",
  "src/components/admin/kit/{page,data-table,table-scroll}.tsx",
  "src/components/admin/config/config-home-view.tsx",
  "src/components/dash/{tiles,team-map-plate,final-submit-panel}.tsx",
  "src/components/reports/tile-meta.tsx",
  "src/lib/ui/**/*.ts",
];

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
    files: CALM_GLASS_FILES,
    plugins: { colors },
    rules: { "colors/no-raw-colors": "error" },
  },
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/consistent-type-imports": "error",
    },
  },
];

export default config;
