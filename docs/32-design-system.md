# 32 — Design direction: "Calm Glass" (binding, 2026-10-07; tokens v0 by the lead, v1 in docs/design/)

**Sponsor ask:** the UI must look fantastic and modern, in the spirit of Apple's interfaces: clean, spacious, glassy (glassmorphism), with smooth motion, in all four front ends (SR, AMO, TSO apps; web dashboard; admin portal). **Constraint that comes with it:** the SR app runs a full day on a shared 2 GB phone in sunlight on a 2 GB data pack, so the look must never cost speed, legibility or battery (docs/04, docs/31 s1).

## 1. Principles
1. **Clarity first.** One primary action per screen, large type for numbers, generous space, plain Bangla labels. A salesperson in the sun with one hand must read and tap without thinking.
2. **Layered glass, restrained.** Soft translucent surfaces over a calm gradient background: hairline inner border, gentle shadow, large radius. Glass is a surface treatment, not decoration on every element.
3. **Depth with meaning.** Elevation shows hierarchy (page, card, sheet, dialog); a sheet that covers the page is the only place a strong blur is allowed.
4. **Motion that explains.** 150 to 250 ms spring or ease-out transitions, shared-element between list and detail, haptic tick on confirm; no decorative loops; respect the system "remove animations".
5. **Bangla-native.** Typography tuned for Bengali (taller line height, no tight letter spacing), Bengali digits per locale, labels never truncated mid-conjunct.
6. **Offline is visible and calm.** A small, steady status chip (Offline, Syncing, Synced, N waiting) on every main screen: never a blocking modal.

## 2. Glass tiers (performance contract)
| Tier | Where | Treatment | Rule |
|---|---|---|---|
| **A, full glass** | web (desktop browsers), phones of performance class high (Android 12 and later, 4 GB+, not in battery saver) when `cfg.app.ui_glass` allows | real backdrop blur on sheets, nav bars and cards, vibrancy tint, animated highlights | blur radius and number of blurred layers capped; measured on a reference high-end phone |
| **B, glass-lite (DEFAULT on field phones)** | Galaxy A06, A07, Honor X5c Plus and similar | tinted translucent surfaces, soft gradient sheen, hairline borders, soft shadows, **no runtime blur layers** | must hold the frame-time gate on the A06 (docs/31 s1) |
| **C, solid** | battery saver, "reduce transparency", sunlight high-contrast theme, any phone that fails the frame-time probe | opaque surfaces, same layout, same radii | text contrast at least WCAG AA 4.5:1, key numbers AAA |
The tier is chosen at runtime (device class, battery saver, accessibility settings) and can be forced by the admin config key `cfg.app.ui_glass` (`auto`, `lite`, `off`) and by a user setting. The same screens exist in all tiers; only the surface treatment changes.

## 3. Tokens v0 (the design workflow refines them into docs/design/tokens.md v1; lanes use the names, not hard-coded values)
- **Colour roles** (light / dark): `bg.gradient` (a calm cool gradient / deep slate gradient), `surface.glass` (white 62 percent / white 12 percent), `surface.solid`, `border.hairline` (white 45 percent / white 14 percent), `text.primary`, `text.secondary`, `accent` (a confident blue), `success`, `warning`, `danger`, `offline`. Money and quantity numbers use `text.primary` at the largest weight.
- **Shape:** radius 12 (chips), 20 (cards), 28 (sheets), full for buttons; hairline 1 dp.
- **Elevation:** four levels with soft, low-opacity shadows; no heavy drop shadows.
- **Type** (Noto Sans Bengali plus the Latin face the kit bundles, already budgeted in docs/31): display 34/40, title 22/30, body 16/24 (Bangla 16/26), caption 13/18; tabular figures for money.
- **Spacing:** 4 dp grid; screen padding 16 dp; touch targets at least 48 dp, primary actions 56 dp, bottom-aligned for one-hand use.
- **Motion:** `fast` 150 ms, `base` 220 ms, `sheet` 320 ms; spring for sheets; reduced-motion variant is a plain fade.

## 4. Rules for the lanes
- Android: build every screen from the `core-ui` kit (N-023, lane android-core-ui). No screen defines its own colours, radii or type. The kit exposes the glass tier through one `GlassSurface` composable. A backdrop-blur library (for example Haze) may be used for tier A only, and only if it passes the A06 frame-time probe without being loaded on tier B and C devices; adding a dependency needs a request file.
- Web: Tailwind with the same token names as CSS variables; `backdrop-filter` only on tier A, with a `@supports not` and a `prefers-reduced-transparency` fallback to solid; charts follow the `dataviz` skill (colour-blind safe, light and dark).
- Every screen ships its empty, loading, offline, error and long-text (Bangla) states, previews for the three tiers and for light and dark, and a screenshot or semantics test that runs in CI.
- Accessibility: contrast gates above, 200 percent font scale without clipping, TalkBack labels, no information by colour alone.
- The owner reviews the look on the real phones; the lead posts the screens in `docs/status/device-checks.md`.

## 5. Process
A design workflow produces `docs/design/` (tokens v1, Android glass spec, web glass spec, screen specs and an HTML preview of the key screens in light, dark, and the three tiers). The owner reviews the preview and says what to change; the kit and the web theme follow the approved version. Until v1 is approved the kit uses these v0 token names.
