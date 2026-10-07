# Web glass spec: Calm Glass for the dashboard and admin portal (v1 draft, 2026-10-07)

Binding inputs: `docs/32` (direction, tiers), `docs/design/tokens.md` v1 (names and values; **it wins over this file on any difference**), `docs/31` s1 (gates), `CLAUDE.md`. This file maps those tokens to CSS variables and Tailwind v4, and specifies the web-only parts: layout, navigation, tables, forms, the configuration console, charts, motion and states. Anything marked **+web** is a web extension that `tokens.md` should adopt or reject (list in s14). The sister file is `docs/design/android-glass.md`.

Checked, not eyeballed: web-only contrast pairs (s2.4) were computed from the tokens.md values over the worst backdrop; the chart palette was run through the dataviz validator on the real chart surfaces; the CSS blocks and class recipes of this file compile with Tailwind 4.3, the inline script parses and the TypeScript snippet type-checks. Repo today: Next 16, React 19, Tailwind 4.3 (CSS-first), Noto Sans Bengali Variable bundled, `components/shell.tsx`, `components/map-panel.tsx`, `components/admin/kit/*`, config pages under `app/admin/config/*`. **No new runtime dependency**: charts are inline SVG.

## 1. Stance for the web (what differs from the phones)

1. **Blur only where content scrolls behind a surface** (tokens s3 principle). A blurred smooth gradient looks like the gradient, so cards, the sidebar, dialogs and popovers never blur; the top bar, the stuck table header, the map panel and sheets do, and at most two at a time.
2. **Data sits on near-opaque glass (+web `surface.data`, 92 percent)**, so table and chart contrast never depends on what is behind.
3. **Desktop first, usable at 360.** Design target 1366 x 768 (common office laptop) and 1920 x 1080; every page also works at 360 x 800 for an AMO on a phone.
4. **Density is a user setting** (s8.2); 8,500-user tables need it.
5. **Offline on the web is a calm chip, not a queue.** Admin writes need the server: offline, the page stays readable (data as of HH:MM) and write controls are `aria-disabled` with the reason. Never a blocking modal.
6. **Same words as the phones.** Gross, offer discount, DRP discount, QC deduction and net payable keep one Bangla label each (UI-SR-07). Strings live in the i18n layer; examples below are illustrations.
7. **Signature details that make it read as Apple-like** (all specified below): a large page title that condenses into the top bar; bars with a 4 percent accent vibrancy tint and `blur` plus `saturate`; a lit top edge and soft sheen on every glass surface; 12, 20 and 28 radii with soft cool shadows; pill buttons with a lit gradient and a sliding-thumb segmented control; floating inset sheets with a spring entry; a calm gradient with two glows behind everything; very large bold numerals; generous 20 to 24 px padding; the map panel floating over the map.

## 2. Tokens and Tailwind

### 2.1 Files
`web/src/styles/tokens.css` (variables, themes, tiers), `glass.css` (`@utility` classes), `globals.css` imports both after `@import "tailwindcss"` and keeps the font import. The values below are copied from `tokens.md` s2 to s5 (light, dark; sunlight by reference). Proposed: the kit lane exports one `tokens.json` that generates both the Kotlin and the CSS values, with a test that fails when the `[data-theme="dark"]` and the media-query dark blocks differ.

### 2.2 CSS variables (docs/32 s3 names, dots become hyphens: `surface.glass.strong` is `--surface-glass-strong`)
```css
:root, :root[data-theme="light"] { color-scheme: light;
  --bg-gradient: radial-gradient(circle 90vw at 12% 6%, rgb(156 194 255 / .50), rgb(156 194 255 / 0)),
                 radial-gradient(circle 80vw at 95% 78%, rgb(201 184 255 / .30), rgb(201 184 255 / 0)),
                 linear-gradient(168deg, #D8E7FB 0%, #E8EFFA 50%, #F3F5FB 100%);
  --bg-solid: #E8EFFA;
  --surface-glass: rgb(255 255 255 / .62);  --strong-rgb: 255 255 255;
  --surface-solid: #FFFFFF;  --surface-solid-raised: #F2F5FA;  --scrim-a: .32;
  --border-hairline: rgb(255 255 255 / .45);  --border-hairline-top: rgb(255 255 255 / .75);  --border-hairline-bottom: rgb(11 27 51 / .10);
  --border-divider: rgb(11 27 51 / .08);  --border-solid: rgb(11 27 51 / .30);  --border-input: #66768F;  --border-focus: #0A58CC;
  --text-primary: #0B1B33;  --text-secondary: #475569;  --text-disabled: #8793A6;  --text-on-accent: #FFFFFF;
  --accent: #0A58CC;  --accent-hi: #1B68DC;  --accent-pressed: #0846A8;  --accent-container: #DCE9FD;  --accent-on-container: #0A3F94;
  --success: #0B7A45;  --success-container: #D6F0E1;  --success-on-container: #0A5A33;
  --warning: #9A5200;  --warning-container: #FFE9C7;  --warning-on-container: #7A4300;
  --danger: #C0182D;   --danger-container: #FDDDE1;   --danger-on-container: #8E1224;
  --offline: #53627C;  --offline-container: #E3E8F0;  --offline-on-container: #3F4C63;
  --state-pressed: rgb(11 27 51 / .08);  --state-disabled-fill: #DCE2EB;  --state-disabled-label: #515E73;
  --state-skeleton: rgb(11 27 51 / .07);  --state-skeleton-sweep: rgb(255 255 255 / .45);
  /* +web */
  --surface-data: rgb(255 255 255 / .92);
  --card-fill: linear-gradient(180deg, rgb(255 255 255 / .30) 0, rgb(255 255 255 / 0) 45%),
               linear-gradient(180deg, rgb(255 255 255 / .66), rgb(255 255 255 / .58));          /* tier A and B card */
  --vibrancy-tint: rgb(10 88 204 / .04);
  --lit: 0 0 0 .5px var(--border-hairline-bottom), inset 0 1px 0 var(--border-hairline-top);
  --elev-card: 0 1px 3px rgb(11 27 51 / .06), 0 2px 10px rgb(11 27 51 / .10);
  --elev-sheet: 0 2px 6px rgb(11 27 51 / .08), 0 8px 24px rgb(11 27 51 / .14);
  --elev-dialog: 0 4px 8px rgb(11 27 51 / .10), 0 8px 32px rgb(11 27 51 / .18); }
:root[data-theme="dark"] { color-scheme: dark;       /* also inside @media (prefers-color-scheme: dark) { :root:not([data-theme]) { ... } } */
  --bg-gradient: radial-gradient(circle 90vw at 12% 6%, rgb(29 78 216 / .18), rgb(29 78 216 / 0)),
                 radial-gradient(circle 80vw at 95% 78%, rgb(91 53 213 / .10), rgb(91 53 213 / 0)),
                 linear-gradient(168deg, #16233F 0%, #0F1A2E 50%, #0A1220 100%);
  --bg-solid: #0F1A2E;
  --surface-glass: rgb(255 255 255 / .12);  --strong-rgb: 26 36 56;
  --surface-solid: #141D30;  --surface-solid-raised: #1C2740;  --scrim-a: .56;
  --border-hairline: rgb(255 255 255 / .14);  --border-hairline-top: rgb(255 255 255 / .22);  --border-hairline-bottom: rgb(255 255 255 / .06);
  --border-divider: rgb(255 255 255 / .08);  --border-solid: rgb(255 255 255 / .24);  --border-input: #8394B0;  --border-focus: #84B6FF;
  --text-primary: #F1F5FB;  --text-secondary: #A9B6CB;  --text-disabled: #6B7A93;  --text-on-accent: #05122B;
  --accent: #84B6FF;  --accent-hi: #A0C8FF;  --accent-pressed: #68A2F8;  --accent-container: #1B3568;  --accent-on-container: #BBD6FF;
  --success: #4CD38B;  --success-container: #12382A;  --success-on-container: #9BE7BF;
  --warning: #FFB84D;  --warning-container: #45330F;  --warning-on-container: #FFD58F;
  --danger: #FF7A8A;   --danger-container: #4A1B25;   --danger-on-container: #FFB3BE;
  --offline: #9FB0CB;  --offline-container: #27324A;  --offline-on-container: #C3D0E6;
  --state-pressed: rgb(255 255 255 / .10);  --state-disabled-fill: #2A3550;  --state-disabled-label: #97A6BF;
  --state-skeleton: rgb(255 255 255 / .10);  --state-skeleton-sweep: rgb(255 255 255 / .12);
  --surface-data: rgb(20 29 48 / .94);
  --card-fill: linear-gradient(180deg, rgb(0 0 0 / 0) 45%, rgb(0 0 0 / .14) 100%), rgb(255 255 255 / .12);
  --vibrancy-tint: rgb(132 182 255 / .04);
  --lit: 0 0 0 .5px var(--border-hairline-bottom), inset 0 1px 0 var(--border-hairline-top);
  --elev-card: 0 1px 3px rgb(0 0 0 / .25), 0 2px 10px rgb(0 0 0 / .35);
  --elev-sheet: 0 2px 6px rgb(0 0 0 / .40), 0 8px 24px rgb(0 0 0 / .50);
  --elev-dialog: 0 4px 8px rgb(0 0 0 / .45), 0 8px 32px rgb(0 0 0 / .55); }
```
`:root[data-theme="sunlight"]` takes the third column of `tokens.md` s2 (flat `--bg-gradient: none`, every glass surface 100 percent opaque, `--border-solid` 1.5 px edges, no shadows) and forces tier C. It is the "High contrast (sunlight)" choice in the theme menu and the default when the user theme is `system` and `prefers-contrast: more` matches. The glow radii are `vw` because the gradient lives on one fixed full-viewport layer, `<div class="fixed inset-0 -z-10 bg-page" aria-hidden>` in the root layout, so scrolling never repaints it. Banding of the light gradient is checked on a 1366 x 768 office panel (tokens s2.1 note).

### 2.3 Rules for colour use (tokens s1 "colour budget", plus the web case)
- `accent` fills the one primary action of a view or sheet; accent text only for links and secondary-button labels. `success`, `warning`, `danger`, `offline` only for status; **offline is never danger**.
- **Status text uses `*-on-container`** (on its container chip or banner, or on glass); the base status colours are for icons, dots, bars and borders (3:1). Reason: `danger` as text on dark glass is 3.82:1 and `offline` 4.35:1, below AA.
- Text-bearing chips, banners and icon wells are always opaque `*-container`. A selected row or tile is `accent-container` plus a check icon, never colour alone. Glass never nests: a box inside a glass card uses `surface-solid-raised`.

### 2.4 Contrast
Shared pairs: the ledger in `tokens.md` s2.7 is binding (`text.primary` on glass 14.59 light and 8.74 dark, AAA for key numbers; `text.secondary` 6.42 and 4.66; `accent` 5.42 and 4.60; bars at 86 and 92 percent; containers; `border.input`; `border.focus`). **Web-only pairs**, computed 2026-10-07 from the tokens values; worst backdrop light `#BAD5FD` (gradient stop 0 under glow A at 50 percent), dark `#172B5B` (stop 0 under glow A at 18 percent); effective `surface-data` `#F9FCFF` light and `#141E33` dark:
| Pair | Light | Dark | Gate |
|---|---|---|---|
| `text-primary` on `surface-data` | 16.73 | 15.19 | AAA 7 (key numbers) |
| `text-secondary` on `surface-data` (axis labels, helper text in tables) | 7.36 | 8.11 | AA 4.5 |
| `accent` as text on `surface-data` | 6.22 | 8.01 | AA 4.5 |
| `border-input` on `surface-data` | 4.48 | 5.41 | 3 |
| `*-on-container` as text on `surface-glass` (minimum of the four) | 6.88 | 5.66 | AA 4.5 |
| base status colours as icons on `surface-glass` (minimum of the four) | 4.66 | 3.82 | 3 |
| destructive button label on `danger` | 6.16 | 7.45 | AA 4.5 |
| map labels (`text-secondary`) on the map land | 6.69 | 8.47 | AA 4.5 |
| `text-secondary` on the map panel (strong 86 percent) over land / water / a black pin or label | 7.45 / 7.30 / 5.47 | 7.67 / n.a. / 4.85 | AA 4.5 |
| `accent` map pin on the map land | 5.65 | 8.37 | 3 |
The kit's token test (s14) recomputes these from `tokens.css`.

### 2.5 Tailwind v4 mapping (`@theme inline`: utilities read the variable at runtime, so themes and tiers flip with no component code)
```css
@theme inline {
  --font-sans: system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Bengali Variable", sans-serif;   /* +web, s4 */
  --color-solid-page: var(--bg-solid);  --color-glass: var(--glass-flat);  --color-glass-strong: var(--glass-fill-strong);
  --color-solid: var(--surface-solid);  --color-raised: var(--surface-solid-raised);  --color-data: var(--surface-data);
  --color-scrim: var(--surface-scrim);  --color-hairline: var(--border-hairline);  --color-divider: var(--border-divider);
  --color-outline: var(--border-solid); --color-input: var(--border-input);  --color-focus: var(--border-focus);
  --color-primary: var(--text-primary); --color-secondary: var(--text-secondary);  --color-disabled: var(--text-disabled);
  --color-on-accent: var(--text-on-accent);
  --color-accent: var(--accent);  --color-accent-hi: var(--accent-hi);  --color-accent-pressed: var(--accent-pressed);
  --color-accent-container: var(--accent-container);  --color-accent-on-container: var(--accent-on-container);
  --color-success: var(--success);  --color-success-container: var(--success-container);  --color-success-on-container: var(--success-on-container);
  --color-warning: var(--warning);  --color-warning-container: var(--warning-container);  --color-warning-on-container: var(--warning-on-container);
  --color-danger: var(--danger);  --color-danger-container: var(--danger-container);  --color-danger-on-container: var(--danger-on-container);
  --color-offline: var(--offline);  --color-offline-container: var(--offline-container);  --color-offline-on-container: var(--offline-on-container);
  --color-pressed: var(--state-pressed);  --color-disabled-fill: var(--state-disabled-fill);
  --color-disabled-label: var(--state-disabled-label);  --color-skeleton: var(--state-skeleton);
  --radius-chip: 12px; --radius-card: 20px; --radius-sheet: 28px;
  --shadow-card: var(--elev-card); --shadow-sheet: var(--elev-sheet); --shadow-dialog: var(--elev-dialog);
  --shadow-accent: 0 4px 10px color-mix(in srgb, var(--accent) 28%, transparent);
  --text-hero: 3rem;                 --text-hero--line-height: var(--lh-hero);          --text-hero--font-weight: 700;   /* +web */
  --text-display: 2.125rem;          --text-display--line-height: var(--lh-display);    --text-display--font-weight: 700;
  --text-numeral: 1.625rem;          --text-numeral--line-height: var(--lh-numeral);    --text-numeral--font-weight: 700;
  --text-title: 1.375rem;            --text-title--line-height: var(--lh-title);        --text-title--font-weight: 700;
  --text-heading: 1.125rem;          --text-heading--line-height: 1.625rem;             --text-heading--font-weight: 700;
  --text-body: 1rem;                 --text-body--line-height: var(--lh-body);          --text-body--font-weight: 400;
  --text-body-strong: 1rem;          --text-body-strong--line-height: var(--lh-body);   --text-body-strong--font-weight: 700;
  --text-caption: var(--fs-caption); --text-caption--line-height: var(--lh-caption);    --text-caption--font-weight: 400;
  --text-label: var(--fs-caption);   --text-label--line-height: var(--lh-caption);      --text-label--font-weight: 700;
  --text-table: var(--table-fs);     --text-table--line-height: var(--table-lh);        --text-table--font-weight: 400;  /* +web, s8.2 */
  --breakpoint-xs: 22.5rem; --breakpoint-3xl: 120rem;                                   /* sm 40, md 48, lg 64, xl 80, 2xl 96 stay default */
  --ease-out-soft: cubic-bezier(.2, 0, 0, 1);  --ease-in-soft: cubic-bezier(.4, 0, 1, 1);  --ease-in-out-soft: cubic-bezier(.4, 0, .2, 1);
  --ease-spring: linear(0, .086 10%, .263 20%, .454 30%, .622 40%, .754 50%, .851 60%, .916 70%, .958 80%, .983 90%, 1);
}
@custom-variant dark (&:where([data-theme="dark"], [data-theme="dark"] *));   /* images and the map only; colours flip by variable */
```
Names are the `tokens.md` s9 utilities (`bg-glass`, `text-primary`, `rounded-card`, `shadow-sheet`, `text-numeral`, `ease-out-soft`...). **Three collisions with `tokens.md` s9, reported in s14:** `border-solid` is already Tailwind's border-style utility, so the `border.solid` colour is exposed as `border-outline` on the web; `ring-focus` needs a solid ring-offset colour that does not exist on glass, so the web kit uses `outline-focus` (outlines follow the radius and the gap stays transparent); `text-disabled` is the one text colour that is not for live text, so lint bans it outside `aria-disabled` controls. Rules: no raw hex, `slate-*` or `bg-white` in `src/` outside `tokens.css` (ESLint rule `no-raw-colors` next to `eslint-rules/`); no `dark:` on a token colour.

### 2.6 Theme, density and tier stamped before first paint (inline script with the CSP nonce; every storage access in try/catch)
```js
(()=>{try{const d=document.documentElement,g=k=>{try{return localStorage.getItem(k)}catch{return null}},q=m=>matchMedia(m).matches;
const t=g('aron.theme')||'system',th=t==='system'?(q('(prefers-contrast: more)')?'sunlight':q('(prefers-color-scheme: dark)')?'dark':'light'):t;
d.dataset.theme=th; d.dataset.density=g('aron.density')||'regular'; d.dataset.glass=th==='sunlight'?'off':(g('aron.glass')||'full');}catch{}})();
```

## 3. Glass tiers on the web (docs/32 s2 and tokens s3 as the contract)
Tier is a surface treatment only; layout, radii and type are identical in all three.
| Surface | **A full** (default on desktop browsers) | **B lite** | **C solid** (and sunlight) |
|---|---|---|---|
| Card, stat card (`glass-card`) | the B recipe, **no blur**: light fill 66 to 58 percent plus sheen 30 percent over the top 45 percent; dark 12 percent plus bottom shade 0 to 14 percent; hairline; `shadow-card` | same | `surface-solid`, 1 px `border-outline`, no shadow |
| Data surface (`glass-data`: tables, charts, key rows) **+web** | `surface-data` 92 / 94 percent | same | solid |
| Top bar, stuck table header (`glass-bar`) | `surface-glass-strong` **86 percent**, **blur 16** (`--glass-blur-bar`) plus `saturate(1.4)` **+web**, vibrancy tint 4 percent, divider edge | strong 92 percent, no blur | solid, 1 px inner edge |
| Sidebar (`glass-rail`) **+web** | strong 86 percent, tint, **no blur** (nothing scrolls behind it) | strong 92 percent | solid |
| Side sheet, drawer (`glass-sheet`) | strong 86 percent, **blur 28** (`--glass-blur-sheet`), scrim at 55 percent of `surface-scrim`, `shadow-sheet` | strong 92 percent, no blur, full scrim | solid, full scrim |
| Map side panel (`glass-panel`) **+web** | strong 86 percent, blur 16; while the map moves 92 percent, no blur | strong 92 percent | solid |
| Dialog, popover, menu, tooltip (`glass-dialog`, `glass-pop`) | strong **94 percent, no blur**, `shadow-dialog` | same | solid |
| Chips, banners, icon wells | opaque `*-container` | same | same |
`glass.floor`: any surface that carries text over moving content has an effective fill of at least 86 percent, which is what the ledger assumes. **Limits (tokens):** at most **2** blurred layers on screen (`glass.blur.maxLayers`), so top bar plus stuck header, or top bar plus map panel; an open sheet sets `data-overlay` on `<html>` and the layers under the scrim lose their blur (1 pass); never blur inside a scrolling list or under a dialog; blur area at most 40 percent of the viewport except a sheet (the panel, 380 x 650, is 23 percent at 1366 x 768); never animate the blur radius, animate `transform` and `opacity`; if a 320 ms sheet entry drops frames on the reference laptop, enter with the 92 percent fill and switch the blur on at `transitionend`.

**Selecting the tier.** `cfg.app.ui_glass` (`auto`, `lite`, `off`) comes from the public config at login and wins over `auto`; a user may only choose more solid than the admin allows. Sunlight, `prefers-reduced-transparency`, `prefers-contrast: more` and `forced-colors` always give C. A missing `backdrop-filter` gives B. Low-end laptops (`deviceMemory <= 2` or `hardwareConcurrency <= 2`) and a failed frame probe (p95 over 32 ms across 30 frames after load) give B. The result is cached in `localStorage` `aron.glass`.
```ts
// web/src/lib/ui/glass-tier.ts (client, no dependency)
export type Glass = "full" | "lite" | "off";
export function pickGlass(admin: "auto" | "lite" | "off", user: "auto" | Glass, theme: string): Glass {
  const mq = (q: string) => matchMedia(q).matches;
  if (admin === "off" || user === "off" || theme === "sunlight" || mq("(prefers-reduced-transparency: reduce)")
      || mq("(prefers-contrast: more)") || mq("(forced-colors: active)")) return "off";
  if (!CSS.supports("backdrop-filter", "blur(1px)") || admin === "lite" || user === "lite") return "lite";
  const m = (navigator as Navigator & { deviceMemory?: number }).deviceMemory ?? 8;
  return m <= 2 || navigator.hardwareConcurrency <= 2 ? "lite" : "full";   // the frame probe may still demote to "lite"
}
```
```css
:root { --glass-flat: var(--surface-glass); --strong-a: .86; --dialog-a: .94; --scrim-k: .55; --card-edge: var(--border-hairline);
  --glass-fill-strong: rgb(var(--strong-rgb) / var(--strong-a));  --glass-fill-dialog: rgb(var(--strong-rgb) / var(--dialog-a));
  --glass-blur-bar: 16px;  --glass-blur-sheet: 28px;
  --bf-bar: blur(var(--glass-blur-bar)) saturate(1.4);  --bf-sheet: blur(var(--glass-blur-sheet)) saturate(1.4);
  --surface-scrim: rgb(0 0 0 / calc(var(--scrim-a) * var(--scrim-k))); }
:root[data-glass="lite"] { --strong-a: .92; --scrim-k: 1; --bf-bar: none; --bf-sheet: none; }
:root[data-glass="off"], :root[data-theme="sunlight"] {
  --glass-flat: var(--surface-solid);  --card-fill: var(--surface-solid);  --card-edge: var(--border-solid);  --vibrancy-tint: transparent;
  --glass-fill-strong: var(--surface-solid);  --glass-fill-dialog: var(--surface-solid);  --scrim-k: 1;
  --bf-bar: none;  --bf-sheet: none;  --elev-card: 0 0 #0000; }
/* Fallbacks. `:root:root` (specificity 0,2,0), placed last, beats data-glass="lite" and "full". */
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  :root:root { --strong-a: .92; --scrim-k: 1; --bf-bar: none; --bf-sheet: none; } }                      /* B look */
@media (prefers-reduced-transparency: reduce), (prefers-contrast: more), (forced-colors: active) {
  :root:root { --glass-flat: var(--surface-solid);  --card-fill: var(--surface-solid);  --card-edge: var(--border-solid);
    --vibrancy-tint: transparent;  --glass-fill-strong: var(--surface-solid);  --glass-fill-dialog: var(--surface-solid);
    --scrim-k: 1;  --bf-bar: none;  --bf-sheet: none;  --elev-card: 0 0 #0000; } }                          /* C look */
@media (prefers-contrast: more), (forced-colors: active) { .bg-page { background: var(--bg-solid); } }
html[data-overlay] .glass-bar { -webkit-backdrop-filter: none; backdrop-filter: none; }               /* under a scrim */
```
`prefers-reduced-transparency` support is still partial, so it is an enhancement: the user setting, `forced-colors`, `prefers-contrast` and the sunlight theme are the dependable paths. (`tokens.md` s9 sends reduced transparency to the 92 percent fill first; the web goes straight to solid, because "reduce transparency" means opaque. Reported in s14.)
```css
@utility bg-page     { background: var(--bg-gradient); }
@utility glass-card  { background: var(--card-fill); border: 1px solid var(--card-edge); border-radius: var(--radius-card); box-shadow: var(--elev-card), var(--lit); }
@utility glass-data  { background: var(--surface-data); border: 1px solid var(--card-edge); border-radius: var(--radius-card); box-shadow: var(--elev-card), var(--lit); }
@utility glass-bar   { background: linear-gradient(var(--vibrancy-tint), var(--vibrancy-tint)), var(--glass-fill-strong);
  -webkit-backdrop-filter: var(--bf-bar); backdrop-filter: var(--bf-bar); }
@utility glass-rail  { background: linear-gradient(var(--vibrancy-tint), var(--vibrancy-tint)), var(--glass-fill-strong); }
@utility glass-sheet { background: linear-gradient(var(--vibrancy-tint), var(--vibrancy-tint)), var(--glass-fill-strong); border: 1px solid var(--card-edge);
  border-radius: var(--radius-sheet); box-shadow: var(--elev-sheet), var(--lit); -webkit-backdrop-filter: var(--bf-sheet); backdrop-filter: var(--bf-sheet); }
@utility glass-panel { background: linear-gradient(var(--vibrancy-tint), var(--vibrancy-tint)), var(--glass-fill-strong); border: 1px solid var(--card-edge);
  border-radius: var(--radius-sheet); box-shadow: var(--elev-sheet), var(--lit); -webkit-backdrop-filter: var(--bf-bar); backdrop-filter: var(--bf-bar); }
@utility glass-dialog { background: var(--glass-fill-dialog); border: 1px solid var(--card-edge); border-radius: var(--radius-sheet); box-shadow: var(--elev-dialog), var(--lit); }
@utility glass-pop   { background: var(--glass-fill-dialog); border: 1px solid var(--card-edge); border-radius: var(--radius-chip); box-shadow: var(--elev-sheet), var(--lit); }
```
Utilities avoid `@apply`, so each is one rule. The optional "animated highlight" of tier A (a pointer-following specular on the one hero card, moved with `opacity` and CSS variables from a rAF-throttled `pointermove`, `@media (hover: hover) and (pointer: fine)`) is off in B, C and reduced motion.

## 4. Typography and numbers (Bangla first)
Weights are **400 and 700 only**, as on the phones (tokens s7); no light weights, no italic. `--font-sans` puts the system UI face first for Latin (SF on Apple, Segoe on Windows, Roboto; zero bytes, **+web**); Bengali glyphs fall through to the bundled Noto Sans Bengali Variable; `:lang(bn)` puts Noto first so a Bangla page is typographically Bangla, digits included. Letter-spacing is 0 everywhere. Minimums: Latin 13 px, Bangla 14 px. The scale is `tokens.md` s7.1 plus two **+web** roles (`hero`, `table`):
| Utility | Latin size / line | Bangla size / line | Weight | Use |
|---|---|---|---|---|
| `text-hero` **+web** | 48 / 56 | 48 / 68 | 700 | the one hero figure of a dashboard |
| `text-display` | 34 / 40 | 34 / 48 | 700 | stat-card values, the day's grand total, large page title |
| `text-numeral` | 26 / 32 | 26 / 38 | 700 | KPI values in compact density, row money |
| `text-title` | 22 / 30 | 22 / 32 | 700 | card, sheet and dialog title |
| `text-heading` | 18 / 26 | 18 / 26 | 700 | section and panel headings |
| `text-body`, `text-body-strong` | 16 / 24 | 16 / 26 | 400, 700 | forms, prose; row titles, buttons |
| `text-caption`, `text-label` | 13 / 18 | 14 / 20 | 400, 700 | helper text; chip text, column headers |
| `text-table` **+web** | 14 / 20 | 14 / 22 | 400 | table cells (density sets it, s8.2) |
```css
:root { --fs-caption: .8125rem; --lh-caption: 1.125rem; --lh-body: 1.5rem; --lh-title: 1.875rem; --lh-numeral: 2rem; --lh-display: 2.5rem; --lh-hero: 3.5rem; }
:lang(bn) { --fs-caption: .875rem; --lh-caption: 1.25rem; --lh-body: 1.625rem; --lh-title: 2rem; --lh-numeral: 2.375rem; --lh-display: 3rem; --lh-hero: 4.25rem; letter-spacing: 0; }
```
(Variables, because `@theme inline` bakes literal values; Bangla needs at least 1.40 em, tokens s7.1.) Hero and stat values use the default proportional figures; the bundled fonts already give every digit one advance so money columns align, and `tabular-nums` stays on table columns and axis ticks as a guard. Never clip: Bangla containers have `min-height` from the line height.
- One formatter (`formatNumber`, `formatMoney` in `lib/i18n`): ASCII stored, Bengali digits per `cfg.locale.digits` (UI-SR-08), money from integer milli-taka with two decimals and the taka sign after the amount at 0.6 em (`৪,৩৯১.০০ ৳`; `৳` is set in the Bengali face even on an English page), quantities with their own unit beside the value and never summed across units (UI-SR-10). Identifiers (codes, usernames, phones, versions) stay ASCII. Digit grouping follows `LocaleDigits` (tokens s10 item 1, Q1).
- Mixed text carries `lang` on the fragment (`<span lang="en">`) so screen readers switch voice.

## 5. Layout grid and breakpoints
| Range | Shell | Grid | Page padding / gap | Notes |
|---|---|---|---|---|
| `xs`/`sm` 360 to 767 | top bar 56 + drawer nav (320 px or 86vw) | 1 column (KPI 2-up from 480) | 16 / 16 | tables scroll in their own region with a frozen first column; sheets are full screen |
| `md` 768 to 1023 | icon rail 72 + content | 6 columns | 24 / 16 | KPI 2-up, charts full width; sheets 480 overlay |
| `lg` 1024 to 1279 | sidebar 264 (collapsible to the 72 rail) | 12 columns | 24 / 24 | KPI 3-up (span 4); primary chart span 12, secondary 6 + 6 |
| `xl` 1280 to 1535 | sidebar 264 | 12 columns | 32 / 24 | KPI 4-up (span 3); chart 8 + 4; at 1366 x 768 content is about 1,038 wide |
| `2xl`/`3xl` 1536+ | sidebar 264 | 12 columns, content max 1600, centred | 32 / 24 | KPI 6-up only at 1760+ content width |
Content widths: dashboards and reports 1600 max, forms and config 1120, reading (tutorials) 720. Spacing is the 4 px grid (`space.N` is Tailwind `N`). Card padding 24 (comfortable), 20 (regular), 16 (compact). Z-index: table header 10, top bar and sidebar 30, popover 40, sheet and side panel 50, dialog 60, toast 70.
```
 1366 x 768
 +------------+------------------------------------------------------------------+
 | sidebar 264|  top bar 56: scope badge | search Ctrl+K | sync chip | lang | theme |
 | glass-rail +------------------------------------------------------------------+
 | Aron       |  Large title (display 34/40)        data as of 08:41   [Export]  |
 | menu groups|  filter bar: Wing > Division > Territory > House > Zone  (1 row) |
 | active =   |  [KPI][KPI][KPI][KPI]   span 3 each                              |
 | container  |  [ chart span 8              ][ chart span 4 ]                   |
 |            |  [ table card: viewport-fit, sticky glass header ]               |
 +------------+------------------------------------------------------------------+
```
Short screens: under 800 px of height the filter bar collapses to one `Filters (3)` button with a popover, so a regular-density table still shows 10 or more rows at 1366 x 768. The page `h1` is the large title; once it scrolls under the top bar, the bar shows the title (`text-heading`, 150 ms fade, from an IntersectionObserver sentinel, not a scroll listener).

## 6. Glass navigation
- **Sidebar** (`glass-rail`, 264, inline-end hairline): brand row 56; group labels `text-label` `text-secondary` (no uppercase transform: Bangla has no case); items 40 tall, radius 12, icon 20 plus label; active item = `bg-accent-container text-accent-on-container font-bold` with `aria-current="page"`; hover `bg-pressed`. Collapsed rail 72: icon only, tooltip on hover and focus, label kept in the accessible name. The role-driven `menuFor(role)` stays the only source of items.
- **Top bar** (`glass-bar`, 56, sticky): scope badge (read-only, server-derived: never an editor of reach), command search (`Ctrl/Cmd+K`: pages, config keys, outlets by id), connection chip (s12.5), language (`বাংলা | EN`), theme (system, light, dark, high contrast), density, user menu. A divider and `shadow-card` appear after the page has scrolled 8 px (`data-scrolled` from the sentinel).
- **Drawer** (below md): `glass-sheet` from the start edge, 320 / 86vw, scrim, focus trap, `Esc` closes.
- **Segmented control** (tabs, density, theme): height 36, radius 12, track `bg-raised`, thumb radius 9 in `bg-solid` with `shadow-card`; the thumb slides with `transform` in `duration-base`; roving tabindex, arrow keys, `role="tablist"` or `radiogroup`.

## 7. Components
### 7.1 Buttons, chips, fields
| Item | Spec |
|---|---|
| Control height `--ctl-h` | 40 regular, 44 comfortable, 32 compact on a precise pointer; **48 on a coarse pointer** (`size.touch`); radius full |
| Primary | lit gradient `accent-hi` to `accent`, `text-on-accent` (5.17 to 6.40 light, 8.97 to 10.82 dark), `text-body-strong`, `shadow-accent`; hover moves the gradient to `accent` to `accent-pressed`; press `scale(.97)` over `--motion-press`. **One per view or sheet.** |
| Secondary | `glass-card` fill, `text-primary` label |
| Tertiary | text only, `text-accent`, underline on hover and focus |
| Destructive | `danger` fill, label `text-on-accent` (6.16 light, 7.45 dark), only in a confirm step that names the object |
| Disabled | `aria-disabled="true"` (stays focusable), `bg-disabled-fill` and `text-disabled-label` (about 5:1), and a visible reason beside it or in a tooltip ("A second approver is needed"); never silent |
| Chip | height 28 (`size.chip`), radius 12, icon 16 plus `text-label`, opaque `*-container` and `*-on-container` |
| Risk chip | `C0` offline container (neutral), `C1` accent container, `C2` warning container, `C3` danger container, each with its words ("C2 after delay", "C3 second approver") |
| Input | height `--ctl-h`, radius 12, `bg-raised`, 1 px `border-input` (3:1 verified), `text-body`, placeholder `text-secondary` |
| Focus | `outline: 2px solid var(--border-focus); outline-offset: 2px` on `:focus-visible` |
Class recipes (kit components wrap these; nothing else in `src/` composes colours):
| Component | Classes |
|---|---|
| Card / data card | `glass-card p-(--card-pad) text-primary` / `glass-data p-(--card-pad) text-primary` |
| Primary button | `inline-flex h-(--ctl-h) items-center gap-2 rounded-full bg-linear-to-b from-accent-hi to-accent px-5 text-body-strong text-on-accent shadow-accent hover:from-accent hover:to-accent-pressed active:scale-[.97] focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus` |
| Secondary button | `inline-flex h-(--ctl-h) items-center gap-2 rounded-full glass-card px-5 text-body-strong text-primary` |
| Chip | `inline-flex h-7 items-center gap-1.5 rounded-chip bg-success-container px-2.5 text-label text-success-on-container` (swap the role) |
| Input | `h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary placeholder:text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus` |
| Nav item | `flex h-10 items-center gap-3 rounded-chip px-3 text-body text-secondary hover:bg-pressed aria-[current=page]:bg-accent-container aria-[current=page]:text-accent-on-container aria-[current=page]:font-bold` |

### 7.2 Forms and validation
Label above the field (`text-label` `text-secondary`; no floating labels), helper below (`text-caption` `text-secondary`), one column up to 560 wide, two columns only for short related pairs. Required fields carry the word "required" in the label (not a lone red star); optional ones are not marked. Reason fields (every write sends a reason, 10 characters minimum per the config registry) show a live counter ("৭/১০"). **States:**
| State | Treatment |
|---|---|
| Default / hover | border `border-input`; hover overlays `bg-pressed` |
| Focus | focus outline plus border `accent` |
| Error | border `danger`, a message strip under the field: octagon icon in `danger`, text `danger-on-container` on `bg-danger-container`, `aria-invalid="true"`, `aria-describedby`; the text says what to do ("Enter 20 to 2,000 metres") |
| Success | a check only for server-verified checks (username free); never on every field |
| Read-only | `bg-raised` without border, copy button where useful |
| Pending save | button shows a 16 px spinner, label stays, `aria-busy` |
Timing: validate on blur, then live; never block typing. On submit failure, focus moves to an error summary (`role="alert"`, links to each field). A `412` or `409` conflict shows a "changed by someone else" card with the new value and a re-apply button, never a silent overwrite.
**Password change** (docs/09 rules: at least 12 characters, mixed case and a number, not one of the last 10, not within 24 h of the last change): a live checklist of four rows under the field, each with an icon (circle, then check), the rule in words, `aria-live="polite"`; submit stays enabled and the server decides. **Login:** one centred `glass-dialog` (420 wide) over the gradient, wordmark, username and password, a second step for the MFA roles (`SUPPORT`, `ADMIN`, `SUPERADMIN`) with one code field (`autocomplete="one-time-code"`, `inputmode="numeric"`, paste works, no per-digit boxes), language switch top right; errors never say which of username or password was wrong.

### 7.3 Cards with big numbers (stat card)
`glass-card`, padding 20 (regular), min-height 128, radius 20, gap 8. Anatomy: label (`text-label` `text-secondary`, sentence case, no colon) / value (`text-display`, `text-primary`, proportional figures; `text-numeral` in compact density) with the unit in `text-body` `text-secondary` / delta chip (arrow icon, signed number, the named period "vs yesterday"; colour is direction times whether up is good, always with arrow and sign) / optional 12-point sparkline (current point `accent`, rest `text-secondary` at 50 percent) / optional meter (6 px high, fill accent then warning then danger by severity, track the lighter step of the same ramp, value also printed). Hero card: `text-hero`, span 6, exactly one per view. Example set from the SR home: outlets visited `৬/৬০`, strike rate `১০%`, net `৪,৩৯১.০০ ৳`. Money and counts are `text-primary` at AAA (14.59 light, 8.74 dark on glass). Loading shows the label and a skeleton value block; error shows the label and "Could not load" with a retry; zero is shown as `০`, never blank.

### 7.4 Overlays
| Overlay | Surface | Size | Rules |
|---|---|---|---|
| Popover, menu, tooltip | `glass-pop` (94 percent, no blur) | auto, 12 px offset, flips | native Popover API (`popover="auto"`), `Esc`, focus returns |
| Side sheet (detail) | `glass-sheet`, floating 12 px inset | 480 md, 560 lg+, full screen below md | `<dialog>` with `showModal()`, focus trap, scrim, header with title and close |
| Dialog | `glass-dialog` | max 480 (confirm) or 640 | title names the object; a typed word only for irreversible bulk actions |
| Toast | `glass-pop`, radius 16, bottom centre | max 3 stacked | `role="status"`, 6 s pausable; errors persist with `role="alert"` and the request id |
| Bulk bar | `glass-pop`, radius 28, floating bottom centre | 560 | appears with a selection; "N selected" and actions |

## 8. Data tables
### 8.1 Anatomy
`glass-data` card holding: toolbar (search `/`, filter chips, column chooser, density, export), the scroll region, footer (pagination). The **table region is the only scroller** (`height: calc(100dvh - var(--topbar-h) - var(--toolbar-h) - var(--footer-h) - 32px)`), so the sticky header always has one scrolling ancestor and the page does not scroll.
```css
.table-scroll { overflow: auto; scroll-padding-top: var(--head-h); overscroll-behavior: contain; }
.table-scroll thead th { position: sticky; top: 0; z-index: 10; height: var(--head-h); background: var(--glass-fill-strong); border-bottom: 1px solid var(--border-divider); }
.table-scroll[data-stuck] thead th { -webkit-backdrop-filter: var(--bf-bar); backdrop-filter: var(--bf-bar); box-shadow: var(--elev-card); }   /* blur only while stuck */
.table-scroll th:first-child, .table-scroll td:first-child { position: sticky; inset-inline-start: 0; z-index: 5; background: var(--surface-data); }
.table-scroll thead th:first-child { z-index: 15; background: var(--glass-fill-strong); }        /* corner cell above both axes */
.table-scroll[data-scrolled-x] td:first-child { box-shadow: 8px 0 12px -8px rgb(11 27 51 / .18); }
html[data-overlay] .table-scroll thead th { -webkit-backdrop-filter: none; backdrop-filter: none; }
```
Header cells: `text-label` `text-secondary`, sort button with arrow icon and `aria-sort`; the header never wraps (a title attribute carries the long form). Rows: bottom hairline `border-divider`; zebra off by default (`bg-raised` at 50 percent on request); hover `bg-pressed`, instant; **selected row `bg-accent-container` plus a check icon** and a 3 px inline-start `accent` bar. **No `backdrop-filter` inside rows, ever.** Where a browser ignores `backdrop-filter` on table cells, the stuck header simply keeps its 86 percent fill; `text-secondary` still measures 5.47:1 over black text (tokens ledger), so nothing breaks. Numeric columns: right-aligned, `tabular-nums`, the unit in the header. Status columns: chip with icon.

### 8.2 Density modes (`html[data-density]`, saved in `localStorage` and the user profile)
| Mode | Row | Cell text (Bangla) | Control height | Card padding | Gap | Default for |
|---|---|---|---|---|---|---|
| comfortable | 52 | 15 / 22 (15 / 22) | 44 | 24 | 24 | touch, tutorials, first run |
| regular (default) | 44 | 14 / 20 (14 / 22) | 40 | 20 | 20 | everyone |
| compact | 36 | 13 / 20 (14 / 20) | 32 | 16 | 12 | chosen by ANALYST, SUPPORT, ADMIN; Bangla stays 14 px so matras never clip |
```css
:root { --topbar-h: 56px; --toolbar-h: 56px; --footer-h: 48px;
  --row-h: 44px; --head-h: 40px; --ctl-h: 40px; --card-pad: 20px; --gap: 20px; --table-fs: .875rem; --table-lh: 1.25rem; }
html[data-density="comfortable"] { --row-h: 52px; --head-h: 44px; --ctl-h: 44px; --card-pad: 24px; --gap: 24px; --table-fs: .9375rem; --table-lh: 1.375rem; }
html[data-density="compact"] { --row-h: 36px; --head-h: 36px; --ctl-h: 32px; --card-pad: 16px; --gap: 12px; --table-fs: .8125rem; --table-lh: 1.25rem; }
html:lang(bn)[data-density="regular"] { --table-lh: 1.375rem; }  html:lang(bn)[data-density="compact"] { --table-fs: .875rem; }
@media (pointer: coarse) { html { --ctl-h: max(var(--ctl-h), 48px); --row-h: max(var(--row-h), 48px); } }
```

### 8.3 Tables of 8,500 and more (users 8,500, devices about 9,850, outlets, memos)
- **Never load the set.** Server-side keyset pagination 50 / 100 / 200 (default 100, so about 1,200 cells render); sort, filter and search on the server; footer "১–১০০ of ৮,৫১২" (exact count up to 10,000, otherwise "about").
- Input to result feedback within 100 ms: debounce search 250 ms, abort the in-flight request, keep the old rows at 60 percent opacity with a 2 px `accent` progress line under the header (`aria-busy` on the region). No skeleton on refetch.
- Filters in the toolbar are chips ("Territory: Banani x"), kept in the URL query so a view can be shared; the scope cascade (Wing, Division, Territory, House, Zone) is bounded by the token scope and never lists what the user cannot reach.
- Columns: a chooser, 6 to 8 visible by default; the first column (name or id) is frozen; horizontal scroll shows an 8 px edge fade. Long text: s12.4.
- Selection: the checkbox sits inside the frozen first cell (40 px, before the name); "select page" by default; "select all 8,512 matching" is an explicit second step that sends the filter to the server, never ids.
- Row activation opens the detail side sheet (`Enter`; click on the primary cell; whole-row click only when no inline controls). Native `<table>` semantics with `aria-sort`; `role="grid"` only for inline-editable matrices (the role x menu editor), with a sticky row and column header.
- Export uses the current filters, shows a progress chip in the toolbar, and never shifts layout.

## 9. Configuration console pages
Shared: every page has a `PageHeading` (title, one-line intro), the effective scope and version in a caption line, and an Audit link. Every write asks for a **reason** and sends `If-Match`; the risk class decides what happens next: C0 and C1 apply at once, C2 waits `cfg.sys.c2_delay_min`, C3 needs a second approver (maker is never checker: `ERR_CFG_SELF_APPROVAL`). TSO and similar roles see "Propose" instead of "Apply" where `cfg.geo.tso_radius_mode` is `propose`. The generated master-data CRUD pages (lane web-admin) and the device, release, audit and day-control pages inherit s7 and s8 unchanged: list is a data table, edit is a form in a side sheet or page, delete is a destructive confirm.

### 9.1 Geofence radius map (`/admin/config/geofence`)
Full-bleed map with a floating glass side panel, the signature screen of the portal.
```
 +--------------------------------------------------+---------------------+
 | map fills the content area (xl: 1102 x 712)      | glass panel 380     |
 |    o    o        .-----.                         | inset 16, radius 28 |
 |       o       o ( o   o )   o                    | Radius  |  What-if  |
 |    o      o      '-----'                         | Scope [Territory v] |
 |  [+] [-]                                         | 100 m  from Division|
 |  Google logo (never covered)                     | slider --o--|---    |
 |                                                  | [ Request change ]  |
 +--------------------------------------------------+---------------------+
   v  Density and calibration (56 px handle; opens a bottom glass drawer)
```
- **Map:** Google Map with a cloud Map ID in two styles. Light: land `#EEF1F6`, water `#CFE0F5`, roads `#FFFFFF` / `#DDE3EE`, labels `#475569` (6.69:1), POI off. Dark: land `#121A2B`, water `#0D2238`, roads `#1F2A40`, labels `#A9B6CB` (8.47:1). Outlet pins: 10 px dot (r 5) in `accent` with a 2 px surface ring; selected r 7 with a 4 px `accent` halo at 24 percent; more than 150 pins in view cluster into 36 px bubbles with a count. The radius circle: 2.5 px `accent` stroke, `accent` at 14 percent fill. The **current** circle is a 2 px `text-secondary` stroke labelled "100 m now"; the **proposed** circle is the accent one labelled "150 m proposed". Outlets that flip to invalid under the proposal change **shape** (square, hollow, 2 px `danger` stroke), not only colour. The panel never covers the Google logo or terms: it sits at the inline end, map controls go `LEFT_BOTTOM`, and `fitBounds` uses `padding.right = 380 + 32`.
- **Glass panel:** `glass-panel`, radius 28, width 380 (320 at lg, bottom sheet below lg), top `var(--topbar-h) + 16px`, max height `100dvh - var(--topbar-h) - 32px`, internal scroll, sticky footer, `role="complementary"` with a label. A chevron collapses it to a 48 px handle so the map is fully visible. **While the map is dragged or zoomed** (`dragstart`, `zoom_changed`) the panel gets `data-map-moving`, which uses the 92 percent fill and no blur, and restores 120 ms after `idle`: blur over a repainting map is the costliest case, and this keeps panning smooth. Top bar plus panel is the two-layer budget.
- **Panel content, top to bottom:** scope picker (today a level select plus an area id; becomes a level select and an area combobox built on `GeoCascade`; levels offered are the key's `scope_levels`); the current radius in `text-display` ("১০০ মি") with a provenance chip ("from Division") and the winning-row link; **radius slider**: log scale between the key's bounds (20 to 2,000 m by default), steps 5 m under 150, 10 m to 500, 50 m above, with a numeric field beside it (`type="range"`, `aria-valuetext="১৫০ মিটার"`, PageUp and PageDown move 50 m); a labelled notch at 150 m ("above this, an increase needs a second approver"); the live **risk chip** (C1 outlet; C2 route, zone, geo class, territory; C3 division, wing, global, or any increase that ends above 150 m); **What-if**: days window (7, 14, 30) and four numbers (evaluated, become valid, become invalid, unchanged) with one 8 px stacked bar (become valid aqua slot 3, become invalid orange slot 2, unchanged `text-secondary` at 35 percent, 2 px gaps, the numbers printed under the bar); **blast radius** line (zones, routes, outlets, users) as four `text-caption` figures; reason field; footer button "Request change" (or "Apply" when C0 or C1 and allowed).
- **Bottom drawer** (Density, Calibration): a segmented control and the existing tables; calibration rows keep the 32 px histogram (bars 6 wide, 2 gap, `aria-label` text) and a "Use 120 m" chip that loads the value into the slider.
- **States:** no scope chosen (empty: "Pick a territory to see its outlets" and three recent scopes as chips); map loading (a gradient plate, no spinner, the panel is already usable); **no Maps key or daily cap reached** (the existing `MapPanel` fallback: a schematic SVG plan of the pins plus the accessible pin list, same panel); map failed (inline notice, retry); long territory names wrap to two lines then ellipsis with `title`; offline (chip, write button disabled with the reason).

### 9.2 Rules and thresholds (`/admin/config/rules`, `keys`, `switches`)
Two panes in a content max of 1120: an area list at the start (Geo, Sale, Sync, Device, Day, Flags; `glass-card`, 240) and the key rows beside it. A search field and filter chips on top: "Changed from default", "Needs approval", "Delivered to devices", "Scheduled".
- **Key row** (`glass-data`, radius 20, padding 20): human label (`text-heading`) in the user's language, the key in mono `text-caption` (`cfg.geo.radius_m`), effective value `text-numeral`, provenance chip (Global, Wing, Territory...), risk chip, bounds hint ("20 to 2,000 m"), and the editor: a switch (44 x 26, "On" or "Off" text beside it, never colour alone), a stepper with unit, an enum segmented control, or a list editor.
- **Edit flow:** editing never saves from the row. Edits collect in a **change-set tray**, a floating bar (`glass-pop`, radius 28, bottom centre, 560): "3 edits ready, highest risk C2: Review and submit". Review opens a side sheet with the diff, one shared reason, `effective from` (now or a date), the blast radius, and the gate in words ("Applies after 15 minutes", "Needs a second approver"). Submit creates one change request (matches `cfg_change.items`).
- **States:** loading (6 skeleton rows), empty filter ("No keys match" and Clear), key retired (muted, "retired" chip), editor locked by role (value shown, the control replaced by "Propose" or a lock icon with the reason), save conflict (s7.2).

### 9.3 Change requests (`/admin/config/changes`)
- **Top:** segmented tabs "Needs me (3)" and "All", then a status filter (pending approval, scheduled, applied, rejected, cancelled, expired, reverted), each chip carrying its icon (clock, calendar, check, cross, slash, hourglass, undo) and word.
- **Table** (s8): columns id, status chip, risk chip, keys (count and the first key), scope, effective from, requested by, age, approver. Regular density, 50 per page.
- **Detail side sheet** (`glass-sheet`, 560): header (id, status, risk); the **diff** as rows "`cfg.geo.radius_m` `১০০ মি`, arrow icon, `১৫০ মি`" with the word "increased" (a `+` or `-` marker plus text, not colour only); blast radius figures; reason; a three-step timeline (requested, approved, applied) with names and times; for C3 a **two-person strip** "1 of 2 approvals" with two named slots; actions Approve, Reject (reason required), Withdraw (maker only). When the viewer is the maker, Approve is `aria-disabled` with the visible reason "You requested this; a different approver is needed". Keyboard: `j` and `k` move the row focus only when focus is not in an input; every key action also has a button.
- **States:** no requests yet (first use), filtered empty ("No requests in this status", Clear), approver decided elsewhere meanwhile (toast and the row refreshes), list error with the request id.

## 10. Charts (dataviz skill: form first, colour last, validated, light and dark)
**Surface rule:** charts render on `glass-data` (effective `#F9FCFF` light, `#141E33` dark) so contrast never depends on the backdrop. The palette below was run through `validate_palette.js` on those surfaces: adjacent CVD worst delta E 9.1 light and 8.4 dark (target 8); normal-vision worst 19.6 and 19.3 (floor 15); all marks at least 3:1 in dark; in light three slots (aqua 2.73, yellow 2.10, magenta 2.61) are under 3:1, so the relief rule applies and **direct labels or the table view are always present**. The first three slots pass all-pairs in both modes, so scatter, map dots and small multiples carry at most three series. Slot 1 is deliberately a different blue from `accent`, so data never looks like a button.
| Slot | Hue | Light | Dark |
|---|---|---|---|
| 1 | blue | `#2A78D6` | `#3987E5` |
| 2 | orange | `#EB6834` | `#D95926` |
| 3 | aqua | `#1BAF7A` | `#199E70` |
| 4 | yellow | `#EDA100` | `#C98500` |
| 5 | magenta | `#E87BA4` | `#D55181` |
| 6 | green | `#008300` | `#008300` |
| 7 | violet | `#4A3AA7` | `#9085E9` |
| 8 | red | `#E34948` | `#E66767` |
CSS variables `--viz-1` to `--viz-8` swap per theme, assigned in this order and never cycled; a 9th series folds into "Other" (`text-secondary` at 55 percent). **Colour follows the entity, not its rank:** `lib/dataviz/entity-colors.ts` fixes a slot per product category id once; filtering never repaints survivors.
- **Sequential** (magnitude: heat cells, density): one blue ramp `#CDE2FB #9EC5F4 #6DA7EC #3987E5 #256ABF #184F95 #0D366B` (light to dark; the anchor flips in dark). **Ordinal** (route buckets by completion; thresholds come from config, never hard-coded): light `#86B6EF #5598E7 #256ABF #104281`, dark `#256ABF #3987E5 #6DA7EC #9EC5F4` (both validated; the end nearest the surface is 2.05:1 light and 3.08:1 dark). **Diverging** (change against a baseline): blue and red poles around a gray midpoint `#F0EFEC` light, `#383835` dark.
- **Status marks in charts** use the fixed viz set (good `#0CA30C`, warning `#FAB219`, serious `#EC835A`, critical `#D03B3B`), always with icon and label, never as a series colour.
- **Ink:** axis, legend and tooltip text `text-secondary` (7.36 and 8.11 on the data surfaces), never the series colour; gridlines 1 px solid `border-divider`; baseline `rgb(11 27 51 / .22)` light and `rgb(255 255 255 / .20)` dark **+web**; no 3D, no gradients on marks, no dual axis, no donut, no shadows.
- **Marks:** bars at most 24 px thick, 4 px rounded data end, square at the baseline, 2 px surface gap between neighbours and stack segments; lines 2 px with round joins; end-dots at least 8 px with a 2 px surface ring; area wash 10 percent; label selectively (end point, extreme, the subject), never every point.
| Aron chart | Form |
|---|---|
| Sales by day, month to date | line, one to three series, crosshair, end-dot label |
| Territory or SR leaderboard | horizontal ranked bars, one hue (slot 1), value at the tip, colour does not encode rank |
| Sales by category | stacked column, entity colours, 2 px gaps, legend plus tooltip (inline labels only where they fit) |
| Geo-valid, force-sale, suspicious counts | lines, or stat cards with a meter; SR positions as map dots using the pin spec of s9.1 |
| Route buckets, daily tracking | ordinal bars |
| Sync health | status rows with icons, not a chart |
- **Interaction:** a vertical hairline crosshair snaps to the nearest x on lines; on bars and cells each mark is the hit target (hover lifts it 8 percent lighter); hit area at least 24 px; one tooltip lists every series at that x, **value first** (`text-body-strong`) then the series name (`text-caption` `text-secondary`) with a short line key, rendered with `textContent` (labels are untrusted). The same on keyboard focus; marks are reachable with arrow keys (roving tabindex). Every chart has a "View as table" button (the accessible equivalent) and `role="img"` with a one-sentence summary.
- **Filters** sit in one row above the charts and scope everything below (date range presets first); a refetch keeps the previous render at 60 percent opacity, no skeleton, no layout jump. Draw once in `--motion-base` on first load only (reduced motion: none). `forced-colors` or the accessibility setting switches to 45 and 135 degree texture fills (never horizontal or vertical).
- Hand-built SVG components with `ResizeObserver`, minimum height 240; no chart library until a request file shows a gap.

## 11. Motion (tokens s8; no decorative loops, no list stagger, no parallax)
| Token | CSS variable | Value | Use |
|---|---|---|---|
| `motion.press` | `--motion-press` | 90 ms linear in, release `fast` | pressed scale: buttons .97, chips .96 |
| `motion.fast` | `--motion-fast` | 150 ms, `ease-out-soft` | hover, chip state, fades, toggles, popover, tooltip |
| `motion.base` | `--motion-base` | 220 ms `ease-out-soft` in, 180 ms `ease-in-soft` out | route change, dialog enter, segmented thumb, toast |
| `motion.sheet` | `--motion-sheet` | 320 ms `ease-spring` (spring 0.85 damping, stiffness 220; the `linear()` curve is its computed step response, 99.7 percent at 320 ms) | side sheet, drawer, bottom drawer |
| `motion.reduced` | `--motion-reduced` | 120 ms linear fade, no transform | every transition under reduced motion |
```css
:root { --motion-press: 90ms; --motion-fast: 150ms; --motion-base: 220ms; --motion-sheet: 320ms; --motion-reduced: 120ms; }
@utility duration-fast  { transition-duration: var(--motion-fast); }
@utility duration-base  { transition-duration: var(--motion-base); }
@utility duration-sheet { transition-duration: var(--motion-sheet); }
@media (prefers-reduced-motion: reduce) { :root { --motion-fast: 120ms; --motion-base: 120ms; --motion-sheet: 120ms; }
  *, ::before, ::after { transition-property: opacity, background-color !important; animation-iteration-count: 1 !important; scroll-behavior: auto !important; } }
```
Route change: the content area fades in over `--motion-base` (opacity plus `translateY(8px)`), once, **not on refetch**, no stagger. Sheet: `translateX(24px)` to 0 plus opacity over `--motion-sheet`; scrim fades in `--motion-base`; dialog `scale(.96)` to 1 plus opacity. Stat values change with a 150 ms cross-fade, no count-up. Loops are functional and bounded: the sync arc while syncing (connection chip), and a skeleton sweep (`state-skeleton-sweep`) capped at 2 cycles then static. The only layout animation is the sidebar collapse (`grid-template-columns`, `--motion-base`, once). List to detail may use the View Transitions API (150 ms) as an enhancement. Reduced motion (`prefers-reduced-motion`, and the in-app setting) removes every transform and loop.

## 12. States
### 12.1 Loading
Skeletons mirror the final layout (card radius 20, table 8 rows) and appear only after 150 ms. Dashboards stream: one Suspense boundary per card so the slowest aggregate never blocks the page (docs/31: dashboards p95 at most 1.5 s). `aria-busy="true"` on the region plus a visually hidden polite "Loading". Map: gradient plate; charts: axes first.
### 12.2 Empty
Four kinds, each with a 32 px outline icon (1.5 stroke), `text-heading` title, one sentence and one action: **first use** ("No change requests yet"), **filtered empty** ("No results for these filters", Clear), **data not here yet** ("Today's data arrives as reps sync. Last sync 08:41"), **no permission** (names the role needed; never shown as "empty").
### 12.3 Error
Per-card errors keep the rest of the page alive. Card: `danger` icon, message in `danger-on-container`, the **request id** with a copy button, Retry. Page: the s7.2 conflict card; session expired is a dialog that keeps unsent form drafts; `403` uses the existing forbidden page; `429` and `503` show "Busy, retrying in N s" and retry once with jitter honouring `Retry-After`. Errors are `role="alert"` only when they follow an action.
### 12.4 Long text
Names and addresses in Bangla and English run to 60+ characters. Regular and comfortable: two lines (`line-clamp: 2`) then ellipsis, the full text in `title` and in the detail sheet; compact: one line plus ellipsis. Never truncate numbers, money, codes or ids (codes get `overflow-wrap: anywhere`). Cells `min-width: 0`, column minimum widths in `rem`. Test fixture: a 64-character Bangla outlet name with conjuncts at 200 percent text.
### 12.5 Connection chip (top bar)
`Live` (success dot), `Reconnecting` (offline icon with the bounded sync arc), `Offline, data as of 08:41` (offline container): 28 px chip, polite live region, never a modal. Offline, write controls are `aria-disabled` with the reason and reads come from the last response. After reconnect the chip returns to Live and unsent form drafts are offered, not auto-sent.

## 13. Keyboard and screen-reader rules (WCAG 2.2 AA, plus the docs/32 gates)
- **Landmarks:** `header`, `nav` with a label, one `main`, `aside` for the map panel; skip links "Skip to content" and "Skip to table"; one `h1` per page, no skipped levels; the document title and a polite announcement update on every route change.
- **Focus:** a visible outline always; sticky bars must not hide it (`scroll-padding-top: calc(var(--topbar-h) + var(--head-h) + 8px)`, criterion 2.4.11); order follows reading order; modal sheets trap and return focus; the map panel is non-modal and reachable before the map; `Esc` closes the topmost overlay only.
- **Targets:** at least 32 x 32 CSS px for pointer controls, 48 on coarse pointers (criterion 2.5.8 asks 24).
- **Patterns:** tabs and segmented controls (arrow keys, roving tabindex), combobox for scope pickers (`aria-expanded`, `aria-activedescendant`), switch (`role="switch"`, `aria-checked`), slider (native range), dialog (`<dialog>`), table (`<caption>`, `scope`, `aria-sort`), toasts (`role="status"`), chips with text labels. Map: a labelled region, a **"Skip map"** link, the pin list as the keyboard and screen-reader alternative, `+` and `-` zoom keys. Charts: summary plus table view (s10).
- **Shortcuts:** `Ctrl/Cmd+K` search, `/` focus table search, `?` list shortcuts; single-key shortcuts are off while focus is in an input and can be switched off in the user menu (criterion 2.1.4).
- **Not colour alone:** status, delta, diff, risk, pin validity, selection and chart series each have an icon, sign, shape or text.
- **Language:** `<html lang>` follows the locale; Bengali digits depend on the voice, so test NVDA with a Bangla voice and give key figures an `aria-label` that spells the value when the voice mis-reads digits (Q3).
- **Zoom and reflow:** 200 percent text without clipping, 400 percent (320 CSS px) reflows to one column; data tables may scroll inside their own region. Honour `prefers-reduced-motion`, `prefers-contrast` (sunlight theme), `forced-colors` (cards get a 1 px `CanvasText` border, shadows removed, focus uses `Highlight`).

## 14. Budgets, tests, migration, reports back
**Budgets** (the web lane records measured numbers in `docs/status/web-*.md`; none are claimed here): CSS after purge at most 40 KB gzip; no Latin web font; the Noto Sans Bengali subset loads for `bn` only; LCP at most 2.5 s p75 on office broadband; interaction to feedback at most 100 ms (INP at most 200 ms); sorting or filtering a 200-row page shows the result within 100 ms of the response on a 2020-class laptop at 1366 x 768; no `box-shadow` or blur animation; `content-visibility: auto` with `contain-intrinsic-size` on below-the-fold dashboard sections.
**CI** (docs/32 s4: every screen ships its states and its tier and theme previews): a Playwright screenshot matrix on Chromium: theme (light, dark; sunlight only with tier off) x tier (full, lite, off) x viewport (360 x 800, 768 x 1024, 1366 x 768, 1920 x 1080) for dashboard home, a table at each density, geofence, rules and change requests; states loading, empty, error, long-text, offline; `axe` on every state; a token test that recomputes the s2.4 pairs from `tokens.css` and fails below the gates; a theme-parity test; the `no-raw-colors` lint; a reduced-motion test (no transition longer than 120 ms); a sheet focus-trap test; the dataviz validator on the `--viz-*` variables.
**Migration of today's code** (restyle PRs by the web lanes; this file touches none of it): `bg-slate-50 text-slate-900` shell to a transparent shell over the fixed `bg-page` layer; `bg-white border-slate-200` panels to `glass-card`; header and sidebar to `glass-bar` and `glass-rail`; `bg-brand-600 hover:bg-brand-700 text-white` to the primary button recipe; `text-brand-700 underline` to `text-accent`; `border-slate-300` inputs to `border-input bg-raised`; `rounded` to `rounded-chip`; the `h-72` map box to the full-bleed map with the panel; keep `--color-brand-*` as aliases of `accent` for one release.

**Reports back to `tokens.md` (log in `DECISIONS.md`):** **+web additions:** `surface.data`, `hero` and `table` type roles, the web control heights (`--ctl-h`), `saturate(1.4)` on blurred bars, the sidebar and map-panel treatments, the system UI Latin face, `--viz-*` chart variables, baseline ink. **Collisions and corrections:** `border-solid` collides with Tailwind's border-style utility (web uses `border-outline`); `ring-focus` becomes `outline-focus` on glass; reduced transparency should map to tier C, not to the 92 percent fill; status colours as text need `*-on-container` on dark glass (3.82:1 and 4.35:1 otherwise). **Lines the web respects from tokens:** two weights only, Bangla minimum 14 px, at most 2 blurred layers, cards never blurred.
**Open questions:** Q1 digit grouping, South Asian (`LocaleDigits`) or Western (docs/20 T-0-121), which also decides the web formatter (tokens s10 item 1); Q2 Google cloud Map IDs and the two styles must be created by the Maps project owner; Q3 Bangla screen-reader digit reading; Q4 whether TSO and AMO use the dashboards on phones enough to promote the 360 layout from "works" to "designed"; Q5 how well `prefers-reduced-transparency` is supported in the browsers AKTCL offices run; Q6 whether the sunlight theme is wanted on the web or only on the phones (default taken: offered as "High contrast").
