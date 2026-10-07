# Aron design tokens v1 ("Calm Glass")

Status: v1 draft for owner review, 2026-10-07. Refines `docs/32-design-system.md` s3 (tokens v0). Names are the docs/32 names (so the kit, the web theme and the screenshots map 1:1); every addition is marked **+**, every changed value is listed in s0. Companion file: `docs/design/android-glass.md` (the `GlassSurface` behaviour and the component list). Lanes use the names, never hard-coded values (docs/32 s4).

Look in one paragraph: a calm cool gradient page with two faint colour glows; white glass cards with a 1 dp lit edge; very large bold numerals for money and quantity; one confident blue (`accent`) used for the single primary action of a screen; status colours only for status; Bangla set with generous line height. Tier A adds real blur on bars and sheets; tier B (field default) looks almost the same without any blur; tier C is solid.

## 0. Changes from v0 (docs/32 s3)

| Item | v0 | v1 | Why |
|---|---|---|---|
| `text.secondary`, all semantic colours | unspecified | exact hex, contrast-checked (s2.7) | v0 had roles only |
| `accent` | "a confident blue" | `#0A58CC` light, `#84B6FF` dark, `#0041B3` sunlight | Apple blue `#007AFF` is 4.0:1 on white, fails AA for text and white button labels |
| Bangla `caption` | 13/18 | **14/20** | Bengali x-height is small; 13 sp Bangla is hard to read in sun. Latin stays 13/18 |
| Bangla `display`, `title` line height | 40, 30 | **48, 32** | Noto Sans Bengali needs 1.40 em to avoid clipping stacked marks (s7.1) |
| `border.hairline` | flat white 45% / 14% | a 1 dp gradient: lit top, faint edge bottom (flat value kept for web) | one stroke gives both highlight and definition on bright pages |
| `surface.glass` | one alpha | adds `surface.glass.strong` + per-tier alphas (s3) | glass over moving content needs an opacity floor for contrast |
| **+** `bg.glow.a/b`, `bg.solid` | none | static radial glows, flat fallback | glass needs something to sit on when there is no blur |
| **+** `sunlight` theme | none | high-contrast light theme, forces tier C | brief: direct sun on an LCD |
| `fast/base/sheet` motion | durations | adds curves, spring constants, press, reduced | s8 |

## 1. Conventions

- **Name to code.** Token `group.role.variant` becomes Kotlin `AronTheme.<group>.<roleVariant>` (dots dropped, lowerCamel), CSS variable `--group-role-variant`, and the Tailwind utility in s9. Example: `surface.glass.strong` is `AronTheme.colors.surfaceGlassStrong`, `--surface-glass-strong`, `bg-glass-strong`.
- **Notation.** Colours are `#RRGGBB` plus alpha in percent. ARGB alpha bytes for code review: 4% `0A`, 6% `0F`, 7% `12`, 8% `14`, 10% `1A`, 12% `1F`, 14% `24`, 18% `2E`, 22% `38`, 24% `3D`, 25% `40`, 28% `47`, 30% `4D`, 31% `4F`, 32% `52`, 35% `59`, 40% `66`, 45% `73`, 50% `80`, 55% `8C`, 56% `8F`, 58% `94`, 62% `9E`, 66% `A8`, 70% `B3`, 72% `B8`, 75% `BF`, 84% `D6`, 86% `DB`, 92% `EB`, 94% `F0`. Kotlin helper: `tokenColor(0x0B1B33, alphaPercent = 8)` = `Color(0xFF0B1B33).copy(alpha = 0.08f)`.
- **Three themes, three tiers, two independent axes.** Theme (what colours): `light` (default), `dark`, `sunlight`. Tier (how surfaces are painted): `A` real blur, `B` glass-lite, `C` solid (docs/32 s2). `sunlight` always forces tier C. Dark follows the system night mode. Sunlight is a user switch (Settings and a one-tap sun control in the Home overflow), stored per device.
- **Dark does not save battery.** The target phones (Galaxy A06 and A07, Honor X5c Plus class) are LCD panels (verify on each device): dark is for comfort at night, not a power feature.
- **Colour budget.** `accent` fills only the primary action; accent text only for links and secondary-button labels; `success`, `warning`, `danger`, `offline` only for status. Offline is never `danger`.
- **Verification method.** WCAG 2.x relative luminance, alpha composited in sRGB (same as Compose and CSS). Worst case taken over the three gradient stops, both glows at peak alpha, the layered glass fill and sheen, and the worst backdrop behind a bar. Computed 2026-10-07 from the exact values below; the kit lane re-asserts them in a JVM test (s10, item 4). The AAA 7:1 gate of docs/32 s2 applies to `text.primary` (money and quantity numbers).

## 2. Colour roles

### 2.1 Page background

| Token | light | dark | sunlight |
|---|---|---|---|
| `bg.gradient` stop 0 (0%) | `#D8E7FB` | `#16233F` | none (flat) |
| `bg.gradient` stop 1 (50%) | `#E8EFFA` | `#0F1A2E` | none |
| `bg.gradient` stop 2 (100%) | `#F3F5FB` | `#0A1220` | none |
| **+** `bg.glow.a` radial, centre (12% w, 6% h), radius 90% w | `#9CC2FF` 50% to 0% | `#1D4ED8` 18% to 0% | none |
| **+** `bg.glow.b` radial, centre (95% w, 78% h), radius 80% w | `#C9B8FF` 30% to 0% | `#5B35D5` 10% to 0% | none |
| **+** `bg.solid` (tier C, sunlight, window background before first frame) | `#E8EFFA` | `#0F1A2E` | `#F1F3F7` |

Gradient geometry: Compose `Brush.linearGradient(colorStops = [0f to s0, 0.5f to s1, 1f to s2], start = Offset(0f, 0f), end = Offset(size.height * 0.21f, size.height))`; web `linear-gradient(168deg, s0 0%, s1 50%, s2 100%)`. Gradient and glows are painted once per window by one `AronBackground` composable at the root, never per screen. Banding: the light gradient spans 27 levels in red over 1,600 px; check on the A06 panel (android-glass s10) and fall back to a pre-dithered 360 x 800 WebP (about 8 KB) only if banding is visible.

### 2.2 Surfaces

| Token | light | dark | sunlight |
|---|---|---|---|
| `surface.glass` (card fill over the page) | `#FFFFFF` 62% | `#FFFFFF` 12% | `#FFFFFF` 100% |
| `surface.glass.strong` (bars, sheets, dialogs over moving content) | `#FFFFFF`, alpha by tier (s3) | `#1A2438`, alpha by tier | `#FFFFFF` 100% |
| `surface.solid` | `#FFFFFF` | `#141D30` | `#FFFFFF` |
| **+** `surface.solid.raised` (inputs, stepper well, inner cards) | `#F2F5FA` | `#1C2740` | `#EEF1F6` |
| `surface.scrim` | `#000000` 32% | `#000000` 56% | `#000000` 56% |

Text-bearing containers (chips, banners, tile icon wells) are always **opaque** (s2.5), so their contrast never depends on the backdrop.

### 2.3 Borders and strokes

| Token | light | dark | sunlight |
|---|---|---|---|
| `border.hairline` (flat, v0 and web) | `#FFFFFF` 45% | `#FFFFFF` 14% | `border.solid` |
| **+** `border.hairline.top` (gradient start) | `#FFFFFF` 75% | `#FFFFFF` 22% | `border.solid` |
| **+** `border.hairline.bottom` (gradient end, the edge) | `#0B1B33` 10% | `#FFFFFF` 6% | `border.solid` |
| **+** `border.divider` (list rows, inset 16 dp) | `#0B1B33` 8% | `#FFFFFF` 8% | `#050A14` 40% |
| **+** `border.solid` (tier C card edge, 1 dp; sunlight 1.5 dp) | `#0B1B33` 30% | `#FFFFFF` 24% | `#050A14` 72% |
| **+** `border.input` (field and stepper well boundary) | `#66768F` | `#8394B0` | `#2B3648` |
| **+** `border.focus` (2 dp ring, 2 dp gap; sunlight 3 dp) | `#0A58CC` | `#84B6FF` | `#000000` |

### 2.4 Text

| Token | light | dark | sunlight |
|---|---|---|---|
| `text.primary` (money and quantity numbers at the heaviest weight) | `#0B1B33` | `#F1F5FB` | `#050A14` |
| `text.secondary` | `#475569` | `#A9B6CB` | `#2B3648` |
| **+** `text.disabled` | `#8793A6` | `#6B7A93` | `#5B6578` |
| **+** `text.onAccent` | `#FFFFFF` | `#05122B` | `#FFFFFF` |

### 2.5 Accent and status

| Token | light | dark | sunlight |
|---|---|---|---|
| `accent` | `#0A58CC` | `#84B6FF` | `#0041B3` |
| **+** `accent.hi` (primary fill gradient top) | `#1B68DC` | `#A0C8FF` | `#0041B3` |
| **+** `accent.pressed` | `#0846A8` | `#68A2F8` | `#002F85` |
| **+** `accent.container` / `accent.onContainer` | `#DCE9FD` / `#0A3F94` | `#1B3568` / `#BBD6FF` | `#CFE0FF` / `#002A73` |
| `success` / `.container` / `.onContainer` | `#0B7A45` / `#D6F0E1` / `#0A5A33` | `#4CD38B` / `#12382A` / `#9BE7BF` | `#005C2E` / `#CFEBD9` / `#00391C` |
| `warning` / `.container` / `.onContainer` | `#9A5200` / `#FFE9C7` / `#7A4300` | `#FFB84D` / `#45330F` / `#FFD58F` | `#7A3E00` / `#FFE0A8` / `#4D2700` |
| `danger` / `.container` / `.onContainer` | `#C0182D` / `#FDDDE1` / `#8E1224` | `#FF7A8A` / `#4A1B25` / `#FFB3BE` | `#A30018` / `#FAD0D6` / `#6B0010` |
| `offline` / `.container` / `.onContainer` | `#53627C` / `#E3E8F0` / `#3F4C63` | `#9FB0CB` / `#27324A` / `#C3D0E6` | `#3B4658` / `#DDE2EA` / `#1F2937` |

`offline` is a calm slate, not an alarm colour (docs/32 principle 6). Status never relies on colour alone: every status chip, banner and row carries an icon and a word.

### 2.6 State layers

| Token | light | dark | sunlight |
|---|---|---|---|
| **+** `state.pressed` (overlay on any surface) | `#0B1B33` 8% | `#FFFFFF` 10% | `#050A14` 12% |
| **+** `state.disabled.fill` | `#DCE2EB` | `#2A3550` | `#D9DEE7` |
| **+** `state.disabled.label` | `#515E73` | `#97A6BF` | `#3B4658` |
| **+** `state.skeleton` | `#0B1B33` 7% | `#FFFFFF` 10% | `#050A14` 10% |
| **+** `state.skeleton.sweep` | `#FFFFFF` 45% | `#FFFFFF` 12% | none (static) |

A selected row or tile uses `accent.container` plus a check icon (never colour alone).

### 2.7 Contrast ledger (worst case, verified)

| Pair | Gate | light | dark | sunlight |
|---|---|---|---|---|
| `text.primary` on `surface.glass` (all stops, glows, layered fill) | 7 (AAA, key numbers) | 14.59 | 8.74 | 19.81 |
| `text.secondary` on `surface.glass` | 4.5 | 6.42 | 4.66 | 12.18 |
| `accent` text or icon on `surface.glass` | 4.5 | 5.42 | 4.60 | 8.72 |
| `text.primary` on `surface.glass.strong` 86% (tier A bar over black, white or grey) | 7 | 12.44 | 9.09 | 19.81 |
| `text.secondary` on `surface.glass.strong` 86% | 4.5 | 5.47 | 4.85 | 12.18 |
| `accent` on `surface.glass.strong` 86% | 4.5 | 4.62 | 4.79 | 8.72 |
| `text.secondary` on `surface.glass.strong` 92% (tier B) | 4.5 | 6.36 | 5.93 | 12.18 |
| `text.onAccent` on `accent.hi` / `accent` / `accent.pressed` | 4.5 | 5.17 / 6.40 / 8.57 | 10.82 / 8.97 / 7.19 | 8.72 / 8.72 / 12.03 |
| every `*.onContainer` on its `*.container` (minimum of five) | 4.5 (sunlight 7) | 6.74 | 8.07 | 9.22 |
| status colour as icon or text on `surface.solid` (minimum of four) | 3 | 5.41 | 6.73 | 8.17 |
| `text.secondary` on `accent.container` (selected row) | 4.5 | 6.18 | 5.83 | 9.14 |
| `state.disabled.label` on `state.disabled.fill` | 4.5 (floor, WCAG exempts disabled) | 5.04 | 4.94 | 7.06 |
| `border.input` on `surface.solid` and `.raised` (WCAG 1.4.11) | 3 | 4.22 | 4.83 | 10.76 |
| `border.focus` on `surface.solid` | 3 | 6.40 | 8.10 | 21.00 |
| `text.primary` on `surface.solid` (tier C) | 7 | 17.23 | 15.38 | 19.81 |

Sunlight meets AAA (7:1) for `text.primary`, `text.secondary`, every container pair and every status colour. Default light and dark meet AA for every text and icon pair above (disabled text is exempt and held to 3:1 visibility) and AAA for `text.primary`.

## 3. Glass tiers: token values

Tier is a surface treatment only; layout, radii and type are identical (docs/32 s2). The tier rules and algorithm are in `android-glass.md` s2 to s4.

**Principle: A equals B plus real blur on moving-content surfaces.** Cards sit on the smooth page gradient, and a blurred smooth gradient is visually identical to the gradient, so cards are never blurred. Blur appears only where content scrolls behind a surface (bars) or where a sheet covers the page (docs/32 principle 3).

| Surface kind | Tier A | Tier B (field default) | Tier C and sunlight |
|---|---|---|---|
| `card`, tile (on the page) | same as B | light: fill vertical gradient `#FFFFFF` 66% to 58%, plus sheen `#FFFFFF` 30% to 0% over the top 45%. dark: fill `#FFFFFF` 12% flat, plus bottom shade `#000000` 0% to 14% over the bottom 55%. Stroke `border.hairline.top` to `.bottom`. Shadow `elev.card` | `surface.solid`, stroke `border.solid`, no shadow (sunlight 1.5 dp, no sheen) |
| `row` in a long list | flat `surface.glass`, no stroke, no shadow, first and last corners `radius.card` | same | `surface.solid`, `border.divider` |
| `bar` (top bar, bottom action bar) | `surface.glass.strong` **86%**, real blur **16 dp** (`glass.blur.bar`), `accent` 4% vibrancy tint, edge stroke | `surface.glass.strong` **92%**, no blur, edge stroke | `surface.solid`, 1 dp `border.solid` on the inner edge |
| `sheet` | `surface.glass.strong` 86%, blur **28 dp** (`glass.blur.sheet`), scrim `surface.scrim` at 55% (light 18%, dark 31%), `elev.sheet` | `surface.glass.strong` 92%, no blur, `surface.scrim`, `elev.sheet` | `surface.solid`, `surface.scrim`, `elev.sheet` (sunlight none, `border.solid`) |
| `dialog` | `surface.glass.strong` 94%, no blur, `surface.scrim`, `elev.dialog` | same | `surface.solid`, `surface.scrim`, `elev.dialog` (sunlight none) |
| chip, banner, tile icon well | opaque `*.container` | same | same |

Limits (hard): at most **2** blurred layers on screen (`glass.blur.maxLayers`); never blur inside a scrolling list or under a dialog; blur area at most 40% of the screen except a sheet; at most 8 shadowed glass cards painted at once on tier B (tiles are shadowless, so a 14-tile Home is exempt; at most 16 shadowless glass surfaces); glass never nests (an inner box on a glass card uses `surface.solid.raised`).

Translucency floor (`glass.floor`): any surface that carries text over moving content has an effective fill of at least 86% (tier A) so the contrast ledger holds over the worst backdrop. Tier A is therefore modestly translucent on bars (14% show-through); the glassy look there comes from the blur, the colour bleed and the lit edge.

## 4. Shape and stroke

| Token | Value | Use | Kotlin | Tailwind |
|---|---|---|---|---|
| `radius.chip` | 12 dp | chips, pack thumbnails, inputs, steppers' value well | `AronTheme.shapes.chip` | `rounded-chip` |
| `radius.card` | 20 dp | cards, tiles, banners, list groups | `AronTheme.shapes.card` | `rounded-card` |
| `radius.sheet` | 28 dp | sheets (top corners), dialogs (all corners) | `AronTheme.shapes.sheet` | `rounded-sheet` |
| `radius.full` | pill (50%) | buttons, stepper buttons, badges | `AronTheme.shapes.full` | `rounded-full` |
| `stroke.hairline` | 1 dp | glass edge, dividers, inputs | `AronTheme.stroke.hairline` | `border` |
| `stroke.strong` | 1.5 dp | tier C and sunlight edges, selected | `.strong` | `border-[1.5px]` |
| `stroke.focus` | 2 dp ring + 2 dp gap (sunlight 3 dp) | keyboard and switch focus | `.focus` | `ring-2 ring-offset-2` |

Concentric rule: inner radius = outer radius minus the inset between them, minimum 8 dp (a 20 dp card with 8 dp inset holds a 12 dp thumbnail; a 28 dp sheet with 16 dp inset holds 12 dp). Strokes are drawn inside the shape bounds (inset by half the width) so they never change layout.

## 5. Elevation (four levels, soft, cool-tinted)

| Token | Compose elevation | Ambient / spot, light (`#0B1B33`) | Ambient / spot, dark (`#000000`) | Design reference | Use |
|---|---|---|---|---|---|
| `elev.page` | 0 dp | none | none | none | page, rows, chips, banners |
| `elev.card` | 4 dp | 6% / 10% | 25% / 35% | y 2, blur 10 | cards, tiles |
| `elev.sheet` | 12 dp | 8% / 14% | 40% / 50% | y -4 (upward), blur 24 | sheets (bars carry no shadow, only the edge stroke) |
| `elev.dialog` | 24 dp | 10% / 18% | 45% / 55% | y 8, blur 32 | dialogs |

Rules: coloured shadows need API 28 or later; on API 26 and 27 the colours are ignored, so use half the elevation. **Never** put a shadow inside a lazy list item (200 shadows at once cost frames on the A06). Tier C: `elev.card` is 0 (the border carries the edge), sheet and dialog keep their shadow. Sunlight: no shadows (1.5 dp borders). A primary button uses an accent-tinted shadow (spot `accent` 28%, elevation 6 dp, 2 dp when pressed).

## 6. Spacing and size (4 dp grid, 360 x 800 dp reference)

| Token | dp | Tailwind | Notes |
|---|---|---|---|
| `space.1` 4, `.2` 8, `.3` 12, `.4` 16, `.5` 20, `.6` 24, `.8` 32, `.10` 40, `.12` 48, `.14` 56 | as named | `p-1` to `p-14` | `space.N` equals Tailwind `N` on the default scale |
| `space.screen` | 16 | `px-4` | screen side padding |
| `space.gutter` | 12 | `gap-3` | gap between tiles and cards |
| `space.card` | 16 | `p-4` | card inner padding |
| `size.touch` | 48 | `min-h-12` | minimum touch target (visual may be smaller; padding fills it) |
| `size.primary` | 56 | `h-14` | primary action height, bottom aligned for one hand |
| `size.chip` | 28 | `h-7` | visual chip height (touch 48) |
| `size.icon` / `.s` / `.l` | 24 / 20 / 32 | `size-6` | icons |
| `size.thumb` | 48 | `size-12` | pack thumbnail (decode at 96 x 96 px, docs/ui-reference UI-SR-19) |
| `size.topBar` | 64 | `h-16` | plus status bar inset |
| `size.bottomBar` | 80 | `h-20` | 12 + 56 + 12, plus navigation inset |
| `size.tile` | min 96 wide x 96 high | | tile spec in android-glass s6 |
| `size.dialog.width` | 312 | `w-[312px]` | on 360 dp: 24 dp each side |
| `size.sheet.maxHeight` | 85% of screen | | |
| `fontScale.reflow` / `.stack` / `.max` | 1.3 / 1.5 / 2.0 | | layout reflow thresholds; 200% must not clip (docs/32 s4) |

Columns for a tile grid: `floor((w - 32 + 12) / (96 + 12))` with a minimum of 2 and a maximum of 4: 3 columns on a 360 dp phone, 2 at font scale 1.5 or more. Edge to edge: content respects system bar insets; the page gradient runs under them.

## 7. Type

Fonts are exactly the four bundled files in `android/core-ui/src/main/res/font` (Noto Sans Bengali Regular and Bold, Noto Sans Latin Regular and Bold). **Only `FontWeight.Normal` (400) and `FontWeight.Bold` (700) exist**: Compose's font matcher would silently map Medium to Regular and SemiBold to Bold, so the kit bans every other weight (lint rule) and no italic is used anywhere.

### 7.1 Scale (sp / line height sp)

| Token | Latin | Bangla | Weight | Use |
|---|---|---|---|---|
| `type.display` | 34 / 40 | 34 / 48 | 700 | the day's grand total, hero numbers |
| **+** `type.numeral` | 26 / 32 | 26 / 38 | 700 | KPI values, row money, stepper value, tile counts |
| `type.title` | 22 / 30 | 22 / 32 | 700 | screen title, sheet and dialog title |
| **+** `type.heading` | 18 / 26 | 18 / 26 | 700 | card and section headers |
| `type.body` | 16 / 24 | 16 / 26 | 400 | default text |
| **+** `type.bodyStrong` | 16 / 24 | 16 / 26 | 700 | row titles, button labels |
| `type.caption` | 13 / 18 | **14 / 20** | 400 | secondary lines, helper text |
| **+** `type.label` | 13 / 18 | 14 / 20 | 700 | chip text, tile labels, column headers |

Why the Bangla line heights: the bundled Noto Sans Bengali has hhea ascent 917 and descent 408 (1.325 em) and win ascent 995 (1.403 em including stacked marks); the Latin face is 1.362 em. Bangla text therefore uses at least 1.40 em, and every Bangla line height above is at least 1.41 em (display 48 / 34 is the tightest). `letterSpacing = 0.sp` everywhere. Minimums: Latin 13 sp, Bangla 14 sp.

**Figures.** Both fonts already set every digit to the same advance (verified from the font files: ASCII 572 regular and 582 bold units per 1,000 em; Bengali digits 592 and 609), so money columns align without a feature flag. Keep `fontFeatureSettings = "tnum"` on `numeral` and `display` as a guard against a future font swap. Bengali digits (`০` to `৯`) are display only (`LocaleDigits.localize`); identifiers (codes, usernames, phone numbers, versions) stay ASCII. The taka sign `৳` is not in the Latin subset: render it with `AronFonts.Bengali` even when the app language is English, at 0.6 em of the number, baseline aligned, after the amount (current app, UI-SR-08), two decimals always (UI-SR-33).

### 7.2 Material slot mapping (keeps `aronTypography(language)` and `TypographyTest` valid)

`displayLarge`, `displayMedium` = display; `displaySmall`, `headlineLarge`, `headlineMedium` = numeral; `headlineSmall`, `titleLarge` = title; `titleMedium` = heading; `titleSmall`, `labelLarge` = bodyStrong; `bodyLarge`, `bodyMedium` = body; `bodySmall` = caption; `labelMedium`, `labelSmall` = label. Every slot uses `AronFonts.forLanguage(language)`.

## 8. Motion

| Token | Value | Use |
|---|---|---|
| `motion.press` | 90 ms, linear in; release `fast` | pressed scale and tint (buttons 0.97, tiles 0.96, stepper buttons 0.92) |
| `motion.fast` | 150 ms, `ease.out` | chip state change, fades, toggles, banner dismiss |
| `motion.base` | 220 ms, `ease.out` in, `ease.in` 180 ms out | screen and shared-element transitions, dialog enter, banner expand |
| `motion.sheet` | 320 ms settle, `spring(dampingRatio = 0.85f, stiffness = 220f)` | sheet enter and drag release (2% settle time 4 / (0.85 x sqrt(220)) = 0.32 s; confirm on the A06) |
| **+** `motion.reduced` | 120 ms linear fade, no transform | every transition when reduced motion is on (android-glass s9) |
| `ease.out` | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` | entering, decelerating |
| `ease.in` | `CubicBezierEasing(0.4f, 0f, 1f, 1f)` | leaving |
| `ease.inOut` | `CubicBezierEasing(0.4f, 0f, 0.2f, 1f)` | movement inside a screen |

No decorative loops, no list entrance stagger, no parallax. Allowed looping motion is functional and bounded: the syncing arc while syncing, and a skeleton sweep capped at 2 cycles.

## 9. Compose, CSS and Tailwind mapping

Kotlin entry point: `AronTheme.colors / .type / .shapes / .stroke / .space / .size / .elevation / .motion / .glass`, each a `@ReadOnlyComposable` getter over a `CompositionLocal` (`LocalAronColors` and so on). `AronTheme(language, mode = AronMode, tier = GlassTier, content)` extends today's `AronTheme(language, content)`; `AronMode` is `Light`, `Dark`, `Sunlight`. `LocalGlassTier` and `LocalReducedMotion` are provided beside them.

| Token | Kotlin | CSS variable | Tailwind utility |
|---|---|---|---|
| `bg.gradient`, `bg.glow.a`, `bg.glow.b`, `bg.solid` | `colors.bgGradient` (`List<Color>`, stops 0, .5, 1), `bgGlowA`, `bgGlowB`, `bgSolid` | `--bg-gradient`, `--bg-glow-a`, `--bg-glow-b`, `--bg-solid` | `bg-page`, `bg-solid-page` |
| `surface.glass`, `.glass.strong`, `.solid`, `.solid.raised`, `.scrim` | `surfaceGlass`, `surfaceGlassStrong`, `surfaceSolid`, `surfaceSolidRaised`, `surfaceScrim` | `--surface-glass`, `--surface-glass-strong`, `--surface-solid`, `--surface-solid-raised`, `--surface-scrim` | `bg-glass`, `bg-glass-strong`, `bg-solid`, `bg-raised`, `bg-scrim` |
| `border.hairline` (+ `.top`, `.bottom`), `.divider`, `.solid`, `.input`, `.focus` | `borderHairline`, `borderHairlineTop`, `borderHairlineBottom`, `borderDivider`, `borderSolid`, `borderInput`, `borderFocus` | `--border-hairline`, `--border-hairline-top`, `--border-hairline-bottom`, `--border-divider`, `--border-solid`, `--border-input`, `--border-focus` | `border-hairline`, `border-divider`, `border-solid`, `border-input`, `ring-focus` |
| `text.primary`, `.secondary`, `.disabled`, `.onAccent` | `textPrimary`, `textSecondary`, `textDisabled`, `textOnAccent` | `--text-primary`, `--text-secondary`, `--text-disabled`, `--text-on-accent` | `text-primary`, `text-secondary`, `text-disabled`, `text-on-accent` |
| `accent` (+ `.hi`, `.pressed`, `.container`, `.onContainer`) | `accent`, `accentHi`, `accentPressed`, `accentContainer`, `accentOnContainer` | `--accent`, `--accent-hi`, `--accent-pressed`, `--accent-container`, `--accent-on-container` | `bg-accent`, `text-accent`, `bg-accent-container`, `text-accent-on-container` |
| `success`, `warning`, `danger`, `offline` (+ `.container`, `.onContainer`) | `success`, `successContainer`, `successOnContainer` (same for the other three) | `--success`, `--success-container`, `--success-on-container` | `text-success`, `bg-success-container`, `text-success-on-container` |
| `state.pressed`, `.disabled.fill`, `.disabled.label`, `.skeleton`, `.skeleton.sweep` | `statePressed`, `stateDisabledFill`, `stateDisabledLabel`, `stateSkeleton`, `stateSkeletonSweep` | `--state-pressed` and so on | `bg-pressed`, `bg-disabled`, `text-disabled-label`, `bg-skeleton` |
| `radius.chip`, `.card`, `.sheet`, `.full` | `shapes.chip`, `.card`, `.sheet`, `.full` (Shapes); `radius.*` as `Dp` | `--radius-chip`, `--radius-card`, `--radius-sheet` | `rounded-chip`, `rounded-card`, `rounded-sheet`, `rounded-full` |
| `stroke.hairline`, `.strong`, `.focus` | `stroke.hairline`, `.strong`, `.focus` (Dp) | `--stroke-hairline` | `border`, `border-[1.5px]`, `ring-2` |
| `elev.page`, `.card`, `.sheet`, `.dialog` | `elevation.page`, `.card`, `.sheet`, `.dialog` (`AronShadow(dp, ambient, spot)`) | `--elev-card`, `--elev-sheet`, `--elev-dialog` | `shadow-card`, `shadow-sheet`, `shadow-dialog` |
| `space.1` to `space.14`, `.screen`, `.gutter`, `.card` | `space.s1` to `s14`, `.screen`, `.gutter`, `.card` (Dp) | `--space-screen` | `p-1` to `p-14`, `px-4`, `gap-3` |
| `size.touch`, `.primary`, `.chip`, `.icon`, `.thumb`, `.topBar`, `.bottomBar` | `size.touch`, `.primary`, `.chip`, `.icon`, `.thumb`, `.topBar`, `.bottomBar` | `--size-touch` | `min-h-12`, `h-14`, `h-7`, `size-6`, `size-12` |
| `type.display`, `.numeral`, `.title`, `.heading`, `.body`, `.bodyStrong`, `.caption`, `.label` | `type.display` ... `type.label` (`TextStyle`, language-aware) | `--type-display` ... | `text-display`, `text-numeral`, `text-title`, `text-heading`, `text-body`, `text-body-strong`, `text-caption`, `text-label` |
| `motion.press`, `.fast`, `.base`, `.sheet`, `.reduced` | `motion.press`, `.fast`, `.base`, `.sheetSpring`, `.reduced` (`Int` ms or `AnimationSpec`) | `--motion-fast`, `--motion-base`, `--motion-sheet` | `duration-fast`, `duration-base`, `duration-sheet` |
| `ease.out`, `.in`, `.inOut` | `motion.easeOut`, `.easeIn`, `.easeInOut` (`Easing`) | `--ease-out` | `ease-out-soft`, `ease-in-soft`, `ease-in-out-soft` |
| `glass.blur.bar`, `.sheet`, `.maxLayers`, `.floor` | `glass.blurBar`, `.blurSheet`, `.maxBlurLayers`, `.floor` | `--glass-blur-bar` (web tier A only) | `backdrop-blur-bar` (tier A only) |

CSS rules for the web theme (details in the web glass spec): `backdrop-filter` only on tier A, guarded by `@supports (backdrop-filter: blur(1px))` and `@media (prefers-reduced-transparency: no-preference)`; otherwise the bar and sheet fall back to `surface.glass.strong` at 92%, then to `surface.solid`.

## 10. Open items (defaults taken, logged for DECISIONS.md)

1. **Number grouping conflict.** `LocaleDigits` groups the South Asian way (`12,34,567`, its KDoc says "as on the current memos") while `docs/20` T-0-121 says Western grouping. The two agree below 1,00,000 (all single-memo amounts) and differ above. Tokens do not decide it; the lead rules, and `LocaleDigits` stays the single source.
2. **Accent is blue (docs/32), not the vendor's red-to-purple.** The current app's red-to-purple header and purple panels are vendor styling (`docs/ui-reference/README.md`: artwork is not reused). Default taken: blue. Revisit only if AKTCL names a brand colour (Q-UI-13).
3. **Two weights only.** Adding a Medium or SemiBold face costs about 140 KB per Bengali weight and is not needed by this scale.
4. **`TokenContrastTest` (kit lane).** A JVM unit test in `core-ui` that asserts the ledger pairs of s2.7 from the token values, so a colour edit cannot silently drop below the gate.
5. **Register `cfg.app.ui_glass`** (`auto`, `lite`, `off`, default `auto`; `off` is the restrictive direction) in `docs/19`; it is named in docs/32 but not yet in the config registry.
6. **Reserved, not designed:** programme dots on outlet rows and any target, loyalty or discount surface (docs/27 deferred). Rows keep an empty leading slot so adding them later changes no layout.

## 11. Web alignment (answer to the extension list in `web-glass.md`)

`web-glass.md` was drafted in parallel from docs/32 v0 and defers to this file on conflicts. Role names and role colours are shared by Android and web; page-gradient stops, glass alphas and blur sizes are per-platform parameters. The web file must adopt these colour values and re-run its own contrast table (its page gradient is brighter than the Android one, so its glass alphas may differ, its role colours may not).

| web-glass name | v1 decision |
|---|---|
| `--accent` `#1D5FD1` / `#4F93FF`, `-hover`, `-ink`, `-wash`, `-on` | replaced by `accent` `#0A58CC` / `#84B6FF` (sunlight `#0041B3`), `accent.pressed` (hover), `accent` itself as link and icon ink (6.40:1 on white), `accent.container` (opaque wash), `text.onAccent` |
| status `fill` / `ink` / `wash` | fill = the role colour, ink = `*.onContainer`, wash = `*.container` (opaque, s2.5); the web's alpha washes and its `#0CA30C`, `#FAB219` fills are replaced. Chart series colours stay in the dataviz palette, outside these tokens |
| `text.secondary` `#3F4B63` / `#C6CFE1`, `text.primary` `#0B1324` / `#F4F7FD` | replaced by s2.4 values |
| `text.tertiary` | **rejected for v1**: in dark, a third level at 4.5:1 on the glass worst case is indistinguishable from `text.secondary`; use `type.caption` in `text.secondary` |
| `surface.data` (92%), `glass.lite` (92%), `glass.strong` (78%) | `surface.glass.strong` with the tier's alpha (92% for data and tier B; the web may keep 78% for tier A bars if its own ledger holds) |
| `surface.sunken`, `border.field`, `border.edge`, `focus-ring`, `scrim`, `page-base` | = `surface.solid.raised`, `border.input`, `border.hairline.bottom`, `border.focus`, `surface.scrim`, `bg.solid` |
| `--elev-1`, `-2`, `-3`, `-4` | = `elev.card`, (web-only raised level for menus), `elev.sheet`, `elev.dialog`; the web may use its multi-layer shadow strings as the CSS form of the same four levels |
| three-blob page gradient (`body::before`) | web keeps three static blobs (no per-frame cost in a fixed layer); Android uses the two glows of s2.1 |
| density, sheen and blur variables, `saturate(1.6)` vibrancy | web-only platform parameters, stay in the web file |
