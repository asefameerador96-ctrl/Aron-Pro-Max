# Aron design tokens v1 ("Calm Glass")

Status: v1, refined 2026-10-07 after the accessibility, performance and visual-critic review (every applied and rejected fix is in `docs/design/CHANGELOG.md`). Pending owner approval. Refines `docs/32-design-system.md` s3 (tokens v0). Names are the docs/32 names (so the kit, the web theme and the screenshots map 1:1); additions are marked **+**, changed values are listed in s0. Companion files: `android-glass.md` (`GlassSurface` behaviour, kit components), `web-glass.md`, `README.md` (how lanes consume this file). Lanes use the names, never hard-coded values (docs/32 s4).

Look in one paragraph: a calm cool gradient page with two faint colour glows; field surfaces that look like frosted paper (96 percent white, a lit 1 dp edge, a soft cool shadow) and a few truly translucent glass pieces (Home tiles, top bar, scrims, the status halo); very large bold numerals for money and quantity; icons in a friendly blue (`accent`), text and the one primary button in a deep blue that stays readable in sunlight; status colours only for exceptions; Bangla set with generous line height. Tier A adds real blur on the top bar (apps) and on bars and sheets (web); tier B (field default) looks almost the same without any blur; tier C is solid.

## 0. Changes from v0 (docs/32 s3)

| Item | v0 | v1 | Why |
|---|---|---|---|
| `text.secondary`, all semantic colours | roles only | exact hex, contrast-checked **raw and under the glare proxy** (s2.7) | docs/32 2a.3 and 2a.4 are binding gates |
| `accent` | "a confident blue" | graphic `#0A58CC` (icons, rings, meters, selection); **+** `accent.text` `#063B84` for text; primary fill is a deep blue (`accent.hi` `#052E6B` to `accent.fill` `#041F49`) | Apple blue `#007AFF` is 4.0:1 on white; a white label on `#0A58CC` is 6.4:1 raw and 3.1:1 under glare, below the key-figure gates (7 and 4.5) |
| `surface.glass` (62 percent) on cards, rows, bars, sheets | one alpha | **+** `surface.glass.field` 96 percent for everything that carries a number, name, status or primary action; `surface.glass` stays for tile backdrops and chrome | docs/32 2a.1 (binding): content is solid, chrome is glass |
| Bangla `caption` | 13/18 | **14/20** | Bengali x-height is small; Latin stays 13/18 |
| Bangla `display`, `title` line height | 40, 30 | **48, 32** | Noto Sans Bengali needs 1.40 em (s7.1) |
| `border.hairline` | flat white 45% / 14% | a 1 dp gradient (lit top, faint edge); sunlight 2 dp | one stroke gives highlight and definition; docs/32 2a.5 asks 2 dp in sunlight |
| **+** `bg.glow.a/b`, `bg.solid` | none | static radial glows, flat fallback | glass needs something to sit on without blur |
| **+** `sunlight` theme | none | high-contrast light theme, forces tier C, **own type step** (s7.1) | docs/32 2a.5 |
| taka sign | 0.6 em | **0.8 em, floor 14 sp**; `.00` at 0.75 em on large money | 0.6 em was 8.4 to 10.8 sp, below the 14 sp floor |
| type weights | 400 and 700 only | roles carry a weight token; **600 is the target for heading, bodyStrong, label** once a variable font passes the gates, 700 is the shipping fallback | six of eight roles were Bold (s7) |
| `fast/base/sheet` motion | durations | adds curves, spring, press, reduced; **bounded loops, no offscreen fades** | s8 |

## 1. Conventions

- **Name to code.** Token `group.role.variant` becomes Kotlin `AronTheme.<group>.<roleVariant>` (dots dropped, lowerCamel), CSS variable `--group-role-variant`, and the Tailwind utility in s9. Example: `surface.glass.field` is `AronTheme.colors.surfaceGlassField`, `--surface-glass-field`, `bg-glass-field`.
- **Notation.** Colours are `#RRGGBB` plus alpha in percent. ARGB alpha bytes: 4% `0A`, 6% `0F`, 7% `12`, 8% `14`, 10% `1A`, 12% `1F`, 14% `24`, 18% `2E`, 22% `38`, 25% `40`, 28% `47`, 30% `4D`, 32% `52`, 35% `59`, 40% `66`, 45% `73`, 50% `80`, 55% `8C`, 58% `94`, 62% `9E`, 66% `A8`, 70% `B3`, 72% `B8`, 75% `BF`, 86% `DB`, 92% `EB`, 94% `F0`, 96% `F5`. Kotlin helper: `tokenColor(0x0B1B33, alphaPercent = 8)`. **Never fade out with `Color.Transparent`**: use the same RGB at alpha 0 (`Color.White.copy(alpha = 0f)`), because Skia interpolates unpremultiplied and a grey band appears.
- **Three themes, three tiers, two independent axes.** Theme: `light` (default), `dark`, `sunlight`. Tier: `A` real blur, `B` glass-lite, `C` solid (docs/32 s2). `sunlight` forces tier C. Dark follows the system night mode. Sunlight is a user switch stored per device; **it is one tap from every main screen**: a 48 dp sun switch in the Home and Login top bars, the first row of the sync detail sheet, a row in Settings (screens-sr s2, s14, s19). A one-time suggestion banner appears when the system brightness setting is 85 percent or more (docs/32 2a.5; read once on resume, no light sensor, no polling). While sunlight is on and the app is in the foreground the window brightness is set to 1.0 and restored on leave.
- **Why sunlight is more than a colour swap (modelled, to be measured in D-UI-01).** Effective contrast in glare is (P x Y_bg + R) / (P x Y_fg + R), with P = 450 cd/m2 (assumed A06-class panel at full brightness, to be measured on the three phones) and R = E x 0.02 / pi reflected. Open shade, 10 klux, R = 64: `text.secondary` on a field card is 6.0:1 in light and 6.4:1 in sunlight. Direct sun, 80 klux, R = 509: 1.80:1 and 1.83:1; `text.primary` 1.85 and 1.88. Colour alone gains under 3 percent where it matters (the v1 draft's glass cards and paler grey lost more, 1.61 to 1.83), so sunlight also sets Bangla body and caption in Bold (the headline stroke at 14 sp, density 2.0, is 2 px in Regular and 4 px in Bold), raises the type one step (s7.1), thickens lines to 2 dp, and forces full brightness.
- **Dark does not save battery.** The target phones are LCD panels (verify on each device): dark is comfort at night, not a power feature.
- **Colour budget.** `accent` (graphic) fills icons, rings, meters, switch tracks and selection; `accent.text` is for text, links, text buttons and secondary-button labels; `accent.hi` to `accent.fill` is only the primary action; `success`, `warning`, `danger`, `offline` only for status, and **status words are set in the `*.onContainer` colour** (the base colours are for icons, dots, bars and borders). **Nominal states are quiet** (no container, no status colour): Synced, printer present, an ongoing task. Containers appear for exceptions: offline, syncing, waiting, storage, overdue. Offline is never `danger`; `danger` is only wrong password, storage nearly full, a destructive confirm, and an invalid typed value. A tile badge (open tasks) is `accent.hi` with `text.onAccent`, not `danger`.
- **Verification method.** WCAG 2.x relative luminance, alpha composited in sRGB (as Compose and CSS). **Glare proxy (docs/32 2a.4):** composite, then blend foreground and background 35 percent toward white, then take the ratio. Gates (docs/32 2a.3, 2a.4): key figures (money, quantity, memo number, outlet name, primary button label) 7:1 raw and 4.5:1 glare; body text 7:1 raw in tier B for field apps and 3:1 glare; secondary text 4.5:1 raw and 3:1 glare; disabled and placeholder 3:1 raw. Sunlight raises every text gate to 7:1 raw. Worst case is taken over the three gradient stops, both glows at peak alpha, both card gradient stops, `raised`, `solid`, and the top bar (tier B 96 percent, tier A 86 percent) over black (light), over white (dark, B) or over mid grey (dark, A). Computed 2026-10-07 with a script that reproduces the v0 ledger to 0.01; the kit lane re-asserts every row in a JVM test (s10 item 4).

## 2. Colour roles

### 2.1 Page background

| Token | light | dark | sunlight |
|---|---|---|---|
| `bg.gradient` stop 0 (0%) | `#D8E7FB` | `#16233F` | none (flat) |
| `bg.gradient` stop 1 (50%) | `#E8EFFA` | `#0F1A2E` | none |
| `bg.gradient` stop 2 (100%) | `#F3F5FB` | `#0A1220` | none |
| **+** `bg.glow.a` radial, centre (12% w, 6% h), radius 90% w | `#9CC2FF` 50% to 0% | `#1D4ED8` 18% to 0% | none |
| **+** `bg.glow.b` radial, centre (95% w, 78% h), radius 80% w | `#C9B8FF` 30% to 0% | `#5B35D5` 10% to 0% | none |
| **+** `bg.solid` (tier C, sunlight) | `#E8EFFA` | `#0F1A2E` | `#F1F3F7` |

Glow strength stays 50 and 30 percent: text sits on the bare page (section labels, captions), and 60 and 40 percent would lower `text.secondary` on the page to 2.97:1 under glare (gate 3.0). Gradient geometry: Compose `Brush.linearGradient(colorStops = [0f to s0, 0.5f to s1, 1f to s2], start = Offset(0f, 0f), end = Offset(size.height * 0.21f, size.height))`; web `linear-gradient(168deg, s0 0%, s1 50%, s2 100%)`.

**Window background (Android).** The window itself is the page: the gradient and both glows are pre-baked offline into one **lossless WebP per theme** at 360 x 800 (`drawable-nodpi`, `drawable-night-nodpi`), used as `android:windowBackground` in `Theme.Aron`; the Compose root is transparent and the background is never nulled. Measured 2026-10-07 on the light theme: no dither 11 KB; ordered 4 x 4 Bayer dither (recommended; confirm at arm's length on the A06, android-glass s10 item 12) 33 KB; 8 x 8 39 KB; random half-level noise 97 KB. The v1 draft's "about 8 KB" was wrong. Two themes are about 66 KB of the +150 KB kit gate (docs/31) and are budgeted separately from code. Tier C and sunlight call `window.setBackgroundDrawable(ColorDrawable(bg.solid))` at the tier change. The live-fill path (gradient and two radial fills drawn in Compose) stays as the fallback only if the A06 shows a cost-free result and the baked image shows banding on a 6-bit-class panel.

### 2.2 Surfaces

| Token | light | dark | sunlight | Use |
|---|---|---|---|---|
| `surface.glass` | `#FFFFFF` 62% | `#FFFFFF` 12% | `#FFFFFF` 100% | Home tile backdrops, empty-state backdrops, web cards (docs/32 2a.1: only where no critical reading sits on it) |
| `surface.glass.strong` | `#FFFFFF`, alpha by tier (s3) | `#1A2438`, alpha by tier | `#FFFFFF` 100% | top bars (chrome), web bars and sheets |
| **+** `surface.glass.field` | `#FFFFFF` 96%, body gradient to `#F6F9FE` 96% | `#141D30` 96% | `#FFFFFF` 100% | every SR, AMO, TSO card, row, action bar and input container: anything with a number, name, status or primary action that sits over the page (sheets and dialogs are fully `surface.solid`) |
| `surface.solid` | `#FFFFFF` | `#141D30` | `#FFFFFF` | tier C, label plates, switch thumbs |
| **+** `surface.solid.raised` | `#F2F5FA` | `#1C2740` | `#EEF1F6` | inputs, stepper well, inner boxes, **any text-bearing control inside a bar, sheet or container** (secondary button, unselected chip) |
| `surface.scrim` | `#000000` 32% | `#000000` 56% | `#000000` 56% | behind sheets and dialogs |

Rule (docs/32 2a.1): glass is allowed only on chrome that carries no critical reading. A control that carries text and sits in a bar, sheet or container is **opaque** (`surface.solid.raised` plus a 1 dp `border.divider`); the glass recipe is allowed for a secondary button directly on the page gradient only. Text-bearing containers (chips, banners, icon wells) are always opaque `*.container`.

### 2.3 Borders and strokes

| Token | light | dark | sunlight |
|---|---|---|---|
| `border.hairline` (flat, web) | `#FFFFFF` 45% | `#FFFFFF` 14% | `border.solid` |
| **+** `border.hairline.top` / `.bottom` (1 dp gradient, lit top, faint edge) | `#FFFFFF` 75% / `#0B1B33` 10% | `#FFFFFF` 22% / `#FFFFFF` 6% | `border.solid` |
| **+** `border.divider` (rows, inset 16 dp) | `#0B1B33` 8% | `#FFFFFF` 8% | `#050A14` 40% |
| **+** `border.solid` (tier C edge 1 dp; **sunlight 2 dp**) | `#0B1B33` 30% | `#FFFFFF` 24% | `#050A14` 72% |
| **+** `border.input` (field and stepper well) | `#66768F` | `#8394B0` | `#2B3648` |
| **+** `border.focus` (2 dp ring, 2 dp gap; sunlight 3 dp) | `#0A58CC` | `#84B6FF` | `#000000` |

### 2.4 Text

| Token | light | dark | sunlight |
|---|---|---|---|
| `text.primary` (money, quantity, names, titles) | `#0B1B33` | `#F8FAFD` | `#050A14` |
| `text.secondary` | `#303D52` | `#C2CCDD` | `#2B3648` |
| **+** `text.disabled` (never for information) | `#738097` | `#6B7A93` | `#5B6578` |
| **+** `text.placeholder` | `#5F6D84` | `#8F9DB5` | `#4A5568` |
| **+** `text.onAccent` (labels on `accent.hi`, `accent.fill`, `danger.fill`) | `#FFFFFF` | `#010712` | `#FFFFFF` |

`text.secondary` is deliberately dark (1.57:1 against `text.primary`): the glare gate (3:1 after the blend, on the page at the glow peak) rules out a lighter slate such as `#475569` (2.74:1). Hierarchy therefore comes from size and weight, not from a pale grey (owner decision in README).

### 2.5 Accent and status

| Token | light | dark | sunlight |
|---|---|---|---|
| `accent` (icons, rings, meters, switch tracks, selection edge; never text) | `#0A58CC` | `#84B6FF` | `#0041B3` |
| **+** `accent.text` (text, links, text buttons, secondary-button labels) | `#063B84` | `#A8CFFF` | `#0041B3` |
| **+** `accent.hi` (primary fill, top stop; flat fill in tier C) | `#052E6B` | `#C9E1FF` | `#002A73` |
| **+** `accent.fill` (primary fill, bottom stop) | `#041F49` | `#B3D4FF` | `#002A73` |
| **+** `accent.pressed` (primary fill pressed) | `#031734` | `#DDEBFF` | `#001B4D` |
| **+** `accent.container` / `accent.onContainer` | `#DCE9FD` / `#0A3F94` | `#1B3568` / `#BBD6FF` | `#CFE0FF` / `#002A73` |
| `success` / `.container` / `.onContainer` | `#0B7A45` / `#D6F0E1` / `#0A5A33` | `#4CD38B` / `#12382A` / `#9BE7BF` | `#005C2E` / `#CFEBD9` / `#00391C` |
| `warning` / `.container` / `.onContainer` | `#9A5200` / `#FFE9C7` / `#7A4300` | `#FFB84D` / `#45330F` / `#FFD58F` | `#7A3E00` / `#FFE0A8` / `#4D2700` |
| `danger` / `.container` / `.onContainer` | `#C0182D` / `#FDDDE1` / `#8E1224` | `#FF7A8A` / `#4A1B25` / `#FFB3BE` | `#A30018` / `#FAD0D6` / `#6B0010` |
| **+** `danger.fill` (destructive button, dialogs only) | `#7A0E1C` | `#FFB3BE` | `#6B0010` |
| `offline` / `.container` / `.onContainer` | `#53627C` / `#E3E8F0` / `#3F4C63` | `#9FB0CB` / `#27324A` / `#C3D0E6` | `#3B4658` / `#DDE2EA` / `#1F2937` |

Why the primary fill is deep: a white label on a lighter blue cannot reach 4.5:1 under the glare proxy (white on `#0A58CC` is 3.14:1; the proxy needs a fill darker than about `#052E6B`). The bright, Apple-like alternative needs a written amendment of docs/32 2a.3 and 2a.4; the preview shows both (`?pri=bright`) so the owner can decide (README, open decision 1). `offline` is a calm slate, not an alarm colour. Status never relies on colour alone: every chip, banner and row carries an icon and a word.

### 2.6 State layers

| Token | light | dark | sunlight |
|---|---|---|---|
| **+** `state.pressed` (overlay on any surface) | `#0B1B33` 8% | `#FFFFFF` 10% | `#050A14` 12% |
| **+** `state.disabled.fill` / `.label` | `#DCE2EB` / `#515E73` | `#2A3550` / `#97A6BF` | `#D9DEE7` / `#3B4658` |
| **+** `state.skeleton` | `#0B1B33` 7% | `#FFFFFF` 10% | `#050A14` 10% |
| **+** `state.skeleton.sweep` | `#FFFFFF` 45% | `#FFFFFF` 12% | none (static) |

A selected row, tile, chip or segment uses `accent.container`, a 1.5 dp `accent` edge and a check icon (never colour alone).

### 2.7 Contrast ledger (worst case, verified, raw / glare)

Each cell is `raw / glare`. Gates: key figures 7 / 4.5, body 7 / 3 (tier B field apps), secondary 4.5 / 3 (sunlight 7 / 3), non-text 3 raw. Backdrops are defined in s1. `TokenContrastTest` asserts every row in all three themes.

| Pair (minimum over the backdrops) | Gate | light | dark | sunlight |
|---|---|---|---|---|
| `text.primary` on field (all page backdrops, both card stops), `raised`, `solid` (key figures) | 7 / 4.5 | 15.77 / 5.11 | 14.21 / 4.65 | 17.50 / 5.84 |
| `text.primary` on the bare page and on the top bar (body) | 7 / 3 | 11.47 / 4.18 | 12.49 / 4.25 | 17.83 / 5.92 |
| `text.secondary` on field, `raised`, `solid`, `accent.container` | 4.5 / 3 | 8.94 / 3.45 | 7.39 / 3.12 | 9.14 / 3.48 |
| `text.secondary` on the bare page and on the top bar (96 and 86 percent) | 4.5 / 3 | 7.30 / 3.04 | 8.07 / 3.23 | 10.97 / 3.91 |
| `accent.text` on field, `raised`, `solid` | 4.5 / 3 | 9.77 / 3.85 | 9.22 / 3.53 | 7.71 / 3.47 |
| `accent.text` on the bare page and on the top bar | 4.5 / 3 | 7.11 / 3.14 | 8.11 / 3.23 | 7.85 / 3.51 |
| `text.onAccent` on the primary fill (top, bottom, pressed) | 7 / 4.5 | 13.05 / 4.56 | 13.24 / 4.99 | 13.30 / 4.68 |
| `text.onAccent` on `danger.fill` | 7 / 4.5 | 11.01 / 4.51 | 11.96 / 4.67 | 12.86 / 5.13 |
| `accent` as icon, ring or meter fill on field, `raised`, `solid` | 3 / none | 5.86 / 2.97 | 7.16 / 3.02 | 7.71 / 3.47 |
| every `*.onContainer` on its `*.container` (minimum of five) | 4.5 / 3 | 6.74 / 3.02 | 8.07 / 3.27 | 9.22 / 3.83 |
| status words (`*.onContainer`) on field and `solid` (minimum of five) | 4.5 / 3 | 7.31 / 3.22 | 8.80 / 3.42 | 11.36 / 4.14 |
| status colour as icon on field and `solid` (minimum of four) | 3 / none | 5.05 / 2.68 | 6.69 / 2.88 | 8.17 / 3.50 |
| `border.input` on `raised`, `solid`, field (WCAG 1.4.11) | 3 / none | 4.22 / 2.32 | 4.83 / 2.43 | 10.76 / 3.86 |
| `border.focus` on `solid` and field | 3 / none | 5.98 / 3.01 | 8.05 / 3.30 | 21.00 / 6.98 |
| `text.disabled` on `raised` and field | 3 / none | 3.65 / 2.15 | 3.42 / 2.01 | 5.18 / 2.58 |
| `text.placeholder` on `raised` and `solid` | 4.5 (docs/32 asks 3) | 4.80 / 2.49 | 5.42 / 2.59 | 6.65 / 2.95 |
| `state.disabled.label` on `state.disabled.fill` | 4.5 floor | 5.04 / 2.51 | 4.94 / 2.42 | 7.06 / 3.00 |

Selected segment and chip: `accent.onContainer` on `accent.container` 7.96 / 8.07 / 9.98 raw, and the `accent` ring on the `raised` track 5.86 / 7.16 / 7.71 raw. Status colour as an icon fails the glare proxy by design (2.68 to 3.50): it is never the only carrier, the word beside it is `*.onContainer` (rows above). Light `accent` on field is 5.86 raw and 2.97 glare: icons only, never text. Web-only pairs are in `web-glass.md` s2.4.

## 3. Glass tiers: token values

Tier is a surface treatment only; layout, radii and type are identical (docs/32 s2). Algorithm and probe: `android-glass.md` s2 to s5. **Principle: content is solid, chrome is glass.** The glass feeling comes from the page (gradient and glows), the lit 1 dp edge, the soft shadow, the translucent Home tiles, the top bar, scrims and the status halo; never from a translucent layer under a number.

| Surface kind | Tier A | Tier B (field default) | Tier C and sunlight |
|---|---|---|---|
| **field card** (hero, outlet card, totals, task card, geo card) | same as B | `surface.glass.field`: light vertical gradient `#FFFFFF` to `#F6F9FE`, both 96%; dark `#141D30` 96% flat plus bottom shade `#000000` 0% to 14% over the bottom 55%. Stroke `border.hairline.top` to `.bottom`. Shadow `elev.card` | `surface.solid`, `border.solid`, no shadow (sunlight 2 dp, no sheen) |
| **tile** (Home) | same as B | glass: light fill `#FFFFFF` 66% to 58% plus sheen `#FFFFFF` 30% to 0% over the top 45%; dark `#FFFFFF` 12% plus bottom shade. Stroke as card, no shadow. **Label sits on an opaque plate** (`surface.solid`, radius 12, 6 dp inset) | `surface.solid`, `border.solid` (plate invisible) |
| **row** in a long list | flat `surface.glass.field`, no stroke, no shadow, `radius.card` on the first and last row | same | `surface.solid`, `border.divider` |
| **top bar** (chrome) | `surface.glass.strong` **86%**, real blur **16 dp** (`glass.blur.bar`), `accent` 4% tint, edge stroke (blur makes the translucency read as glass) | `surface.glass.strong` **96%**, no blur, edge stroke (without blur, ghost text under a 92% bar read as a rendering bug) | `surface.solid`, 1 dp `border.solid` on the inner edge (sunlight 2 dp) |
| **action bar** (bottom; holds a total or the primary action) | `surface.glass.field` 96%, **no blur**, edge stroke | same | `surface.solid`, `border.solid` |
| **sheet**, **dialog** (apps) | `surface.solid` (100%: even 4 percent of the dimmed page behind a 96% sheet showed as ghost text), `surface.scrim`, `elev.sheet` or `elev.dialog` | same | `surface.solid`, `surface.scrim`, **no shadow** (`border.solid` carries the edge) |
| web bar, sheet (office, docs/32 2a.1 allows) | `surface.glass.strong` 86%, blur 16 and 28 dp, scrim at 55% | 96%, no blur | solid |
| chip, banner, tile icon well | opaque `*.container` | same | same |

The bottom action bar is **a layout slot below the list**, never an overlay: rows never scroll under it, so nothing shows through it, and a 24 dp `ScrollEdgeFade` (`glass.edgeFade`: the page colour at 0% to 100% alpha, one gradient rect) softens the end of the list. The top bar is an overlay (the Home header collapses into it); on tier A its 14 percent blurred show-through is allowed chrome (docs/32 2a.1) and the ledger covers it; on tier B only 4 percent shows.

Limits (hard): at most **2** blurred layers on screen (`glass.blur.maxLayers`; in the SR, AMO and TSO apps only the tier A top bar blurs, sheets do not); never blur inside a scrolling list or under a dialog; at most 8 shadowed field cards painted at once on tier B (tiles and rows are shadowless; at most 16 shadowless glass surfaces); glass never nests (an inner box uses `surface.solid.raised`, and a secondary button inside a bar uses the opaque recipe of s2.2).

Opacity floor (`glass.floor`): 96 percent for any surface that carries a number, name, status or primary action in the SR, AMO and TSO apps; 86 percent only for chrome without critical reading (top bar) and for the web (docs/32 2a.1).

## 4. Shape and stroke

| Token | Value | Use | Kotlin | Tailwind |
|---|---|---|---|---|
| `radius.chip` | 12 dp | chips, pack thumbnails, inputs, steppers' value well | `AronTheme.shapes.chip` | `rounded-chip` |
| `radius.card` | 20 dp | cards, tiles, banners, list groups | `AronTheme.shapes.card` | `rounded-card` |
| `radius.sheet` | 28 dp | sheets (top corners), dialogs (all corners) | `AronTheme.shapes.sheet` | `rounded-sheet` |
| **+** `radius.well` | 14 dp on a 44 dp well (11 dp on 36) | squircle icon wells on tiles | `AronTheme.shapes.well` | `rounded-well` |
| `radius.full` | pill (50%) | buttons, stepper buttons, badges | `AronTheme.shapes.full` | `rounded-full` |
| `stroke.hairline` | 1 dp (sunlight 2 dp) | glass edge, dividers, inputs | `AronTheme.stroke.hairline` | `border` |
| `stroke.strong` | 1.5 dp (sunlight 2 dp) | tier C edges, selected ring | `.strong` | `border-[1.5px]` |
| `stroke.focus` | 2 dp ring + 2 dp gap (sunlight 3 dp) | keyboard and switch focus | `.focus` | `ring-2 ring-offset-2` |

Concentric rule: inner radius = outer radius minus the inset, minimum 8 dp (a 20 dp card with a 6 dp inset holds a 12 dp plate). Strokes are drawn inside the shape bounds.

## 5. Elevation (four levels, soft, cool-tinted)

| Token | Compose elevation | Ambient / spot, light (`#0B1B33`) | Ambient / spot, dark (`#000000`) | Design reference | Use |
|---|---|---|---|---|---|
| `elev.page` | 0 dp | none | none | none | page, rows, chips, banners, tiles |
| `elev.card` | 4 dp | 6% / 10% | 25% / 35% | y 2, blur 10 | field cards |
| `elev.sheet` | 12 dp | 8% / 14% | 40% / 50% | y -4, blur 24 | sheets (tier A and B) |
| `elev.dialog` | 24 dp | 10% / 18% | 45% / 55% | y 8, blur 32 | dialogs (tier A and B) |

Rules: coloured shadows need API 28 or later; on API 26 and 27 use half the elevation. **Never** a shadow inside a lazy list item. **Tier C and sunlight: no shadows at all** (the 1 dp `border.solid` plus the scrim carry sheet and dialog edges; a tessellated 24 dp shadow is the most expensive draw on API 26 to 28). A primary button uses an accent-tinted shadow (spot `accent` 28%, 6 dp, 2 dp pressed) on tiers A and B.

## 6. Spacing and size (4 dp grid; wireframes are 360 x 800 dp, the compact variant is 360 x 640)

| Token | dp | Tailwind | Notes |
|---|---|---|---|
| `space.1` 4, `.2` 8, `.3` 12, `.4` 16, `.5` 20, `.6` 24, `.8` 32, `.10` 40, `.12` 48, `.14` 56 | as named | `p-1` to `p-14` | equals Tailwind `N` |
| `space.screen` 16, `space.gutter` 12, `space.card` 16 | | `px-4`, `gap-3`, `p-4` | |
| `size.touch` | 48 | `min-h-12` | **every interactive element is at least 48 x 48 dp**; the visual may be smaller (chip 28 or 40, switch 52 x 32, text button 40) with padding or `minimumInteractiveComponentSize`; neighbouring targets keep a 48 dp pitch |
| `size.primary` | 56 | `h-14` | primary action height, bottom aligned |
| `size.field` | 56 | `h-14` | text field, radio row, switch row |
| `size.segment` | 48 (visual 40) | `min-h-12` | segmented control |
| `size.chip` | 28 | `h-7` | visual height, may grow to 2 lines |
| `size.icon` / `.s` / `.l` | 24 / 20 / 32 | `size-6` | |
| `size.thumb` | 48 | `size-12` | pack thumbnail, decode at 96 x 96 px |
| `size.topBar` | 64 | `h-16` | plus status inset |
| `size.bottomBar` | 80 (compact 72) | `h-20` | 12 + 56 + 12, plus navigation inset; **budget: the pinned bar is at most 25 percent of window height (200 dp on 800)** |
| `size.tile` | 101 x 96 (group "আজকের কাজ"), 101 x 84 (group "অন্যান্য") | | fixed heights below font scale 1.3 |
| `size.meter` | 6 (sunlight 8 plus a 1.5 dp outline) | `h-1.5` | |
| `size.dialog.width` | 312 | `w-[312px]` | |
| `fontScale.cap` / `.tiles2` / `.tiles1` / `.stack` / `.vertical` | 2.0 / 1.3 / 1.9 / 1.3 / 1.5 | | caps; 2 tile columns from 1.3, 1 from 1.9; bars and hero stacks from 1.3; segmented control becomes radio rows from 1.5 |
| `height.compact` | under 700 dp window height | | compact variant (android-glass s6.7, screens-sr s0) |

Bottom bar at font scale (measured arithmetic, nav inset 16): one row 96 dp; a stacked summary line adds 66 dp at 1.0 (caption 20 + numeral 38 + gap 8), giving 162, 179, 191 and 232 dp at 1.0, 1.3, 1.5 and 2.0. **From font scale 1.3 the bar is one column**: a one-line summary (caption and money on one baseline) above a full-width primary, about 160 to 175 dp (20 to 22 percent) at 2.0. A secondary action that does not fit in 200 dp leaves the bar and becomes the last item of the scroll content as a 48 dp text button.

Tile columns: 3 below font scale 1.3 (inner width 93 dp with 4 dp side padding), 2 from 1.3 to below 1.9 (inner 150 dp), 1 from 1.9 (a 64 dp row, well at the start, label wrapping to 2 lines). Measured at 14 sp Bold: "টিউটোরিয়াল" 75 dp at 1.0, 97 at 1.3, 112 at 1.5, 142 at 1.9, 150 at 2.0; "অ্যাটেনডেন্স" 69, 90, 104, 131, 138. Labels break at spaces only, never inside a conjunct.

## 7. Type

Fonts are the four bundled files in `android/core-ui/src/main/res/font` (Noto Sans Bengali Regular and Bold, Noto Sans Latin Regular and Bold; static, no `fvar`). **Shipping v1 uses 400 and 700.** A role may name `weight.semi` (600): until a variable subset passes the gates below the kit renders it as 700. **Gate to adopt the variable font** (Noto Sans Bengali and Latin as one `wght` 400 to 700 subset each, `FontVariation.weight`, API 26 and later): subset size at most the two static Bengali files (278 KB), cold start and the 60-SKU Bangla scroll no worse than 5 percent on the D-12 class phone, no rendering fault on API 26 to 28 devices of the lab. Lint allows 400, 500, 600 and 700 only through type tokens; no italic anywhere. Compose's matcher would otherwise map 600 to 700 silently, so the kit never passes a raw `FontWeight`.

### 7.1 Scale (sp / line height sp; Bangla differs where marked)

| Token | Latin | Bangla | Weight | Sunlight (Latin / Bangla) | Use |
|---|---|---|---|---|---|
| `type.display` | 34 / 40 | 34 / 48 | 700 | same | the day's grand total, hero numbers |
| **+** `type.numeral` | 26 / 32 | 26 / 38 | 700 | 28 / 34 and 28 / 40 | KPI values, row money, stepper value |
| `type.title` | 22 / 30 | 22 / 32 | 700 | same | screen, sheet and dialog title |
| **+** `type.heading` | 18 / 26 | 18 / 26 | 600 (700 fallback) | same | card and section headers, tile group labels |
| `type.body` | 16 / 24 | 16 / 26 | 400 | 17 / 26 and 18 / 28, **Bold** | default text |
| **+** `type.bodyStrong` | 16 / 24 | 16 / 26 | 600 (700 fallback) | 17 / 26 and 18 / 28, Bold | row titles, button labels |
| `type.caption` | 13 / 18 | **14 / 20** | 400 | 15 / 22 and 16 / 24, **Bold** | secondary lines, helper text |
| **+** `type.label` | 13 / 18 | 14 / 20 | 600 (700 fallback) | 15 / 22 and 16 / 24, Bold | chip text, tile labels, column headers, badges |

Sunlight (docs/32 2a.5): the type step above, Bangla body and caption at weight 700 (docs/32 asks at least 500; the bundled Bold file costs nothing and is about 3 to 5 percent wider; line heights are unchanged), hairlines 2 dp, focus ring 3 dp, meter 8 dp with a 1.5 dp outline. Why the Bangla line heights: the bundled Noto Sans Bengali has hhea ascent 917 and descent 408 (1.325 em) and win ascent 995 (1.403 em with stacked marks); the Latin face is 1.362 em. Bangla uses at least 1.40 em: every Bangla line height above is at least 1.41 em. `letterSpacing = 0.sp` everywhere. Minimums: Latin 13 sp, Bangla 14 sp, **taka sign 14 sp**.

**Figures.** Both fonts give every digit the same advance (ASCII 572 and 582 units per 1,000 em; Bengali digits 592 and 609), so money columns align without `fontFeatureSettings = "tnum"`: the kit drops the flag (a separate shaping-cache key on the hottest text) and instead asserts equal digit advances of the four bundled files in a unit test, which fails on a font swap. Bengali digits are display only (`LocaleDigits.localize`); identifiers (codes, usernames, phone numbers, versions) stay ASCII. **Taka sign `৳`:** rendered with `AronFonts.Bengali` even in English, after the amount, baseline aligned, **at 0.8 em of the amount with a 14 sp floor** (hero 27 sp, numeral 21, body 14), joined to the amount with U+00A0 so it can never wrap alone. On `display` and `numeral` money only, the `.00` part is set at 0.75 em (floor 14 sp), same colour, so the zeros do not read as a row of rings in sun. Two decimals always (UI-SR-33). In **dense columns** (Review lines, Memo table) the per-row sign is dropped and `(৳)` goes in the column header; **every total keeps its sign** (current app, UI-SR-08, for totals and printed slips).

### 7.2 Material slot mapping (keeps `aronTypography(language)` and `TypographyTest` valid)

`displayLarge`, `displayMedium` = display; `displaySmall`, `headlineLarge`, `headlineMedium` = numeral; `headlineSmall`, `titleLarge` = title; `titleMedium` = heading; `titleSmall`, `labelLarge` = bodyStrong; `bodyLarge`, `bodyMedium` = body; `bodySmall` = caption; `labelMedium`, `labelSmall` = label. Every slot uses `AronFonts.forLanguage(language)`; the sunlight scale is a second `Typography` selected by `AronMode.Sunlight`.

### 7.3 Numbers never wrap or clip

Measured with the bundled Bengali Bold (hero inner width 296 dp): "৪,৩৯১.০০ ৳" at display 34 sp is 162 dp at 1.0 and 323 dp at 2.0; "১২,৪৫৬.৫০ ৳" 274 dp at 1.5 and 365 at 2.0; "১,২৪,৫৬৭.৫০ ৳" overflows from 1.40; "১২,৩৪,৫৬৭.০০ ৳" from 1.27. Rules: `MoneyText` and `QtyText` are single line, no ellipsis, and the sign is a `SpanStyle` in the same `Text`. Size is `min(base x fontScale, base x 1.5)`, then shrunk to fit with `TextAutoSize.StepBased(minFontSize = 0.7 x base, step 1 sp)` (or the kit's `FitText` over `TextMeasurer` if the Compose version lacks it), with floors display 24 sp, numeral 18 sp, bodyStrong 16 sp. **From font scale 1.3 the label and the value of a hero, totals card or bar summary stack** (label above value), so the value gets the full 296 dp: the longest string then fits at 44 sp and the seed total at 2.0 at 51 sp (232 dp). Chips drop `nowrap`: `heightIn(min = 28 dp)`, label `maxLines = 2`, chip rows and the Tasks card header are `FlowRow` with 8 dp gaps. Golden screenshots cover the amounts 4,391.00, 12,456.50, 1,24,567.50 and 12,34,567.00 at 1.0, 1.5 and 2.0 (android-glass s7.12).

## 8. Motion

| Token | Value | Use |
|---|---|---|
| `motion.press` | 90 ms, linear in; release `fast` | pressed scale and tint (buttons 0.97, tiles 0.96, stepper buttons 0.92) |
| `motion.fast` | 150 ms, `ease.out` | chip state change, fades, toggles, banner dismiss |
| `motion.base` | 220 ms, `ease.out` in, `ease.in` 180 ms out | screen and shared-element transitions, dialog enter, banner expand |
| `motion.sheet` | 320 ms settle, `spring(dampingRatio = 0.85f, stiffness = 220f)` | sheet enter and drag release (confirm on the A06) |
| **+** `motion.reduced` | 120 ms linear fade, no transform | every transition when reduced motion is on (android-glass s9) |
| `ease.out` / `ease.in` / `ease.inOut` | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` / `(0.4f, 0f, 1f, 1f)` / `(0.4f, 0f, 0.2f, 1f)` | entering / leaving / inside a screen |

No decorative loops, no list entrance stagger, no parallax. Allowed looping motion is functional and **bounded**: the syncing arc turns **at most 3 revolutions (3.6 s) after entering Syncing, then holds static** beside the word (the word carries the meaning), and a skeleton sweep is capped at 2 cycles. **No offscreen fades:** a fade of a whole screen, sheet, dialog or banner is `graphicsLayer { alpha }` with `compositingStrategy = CompositingStrategy.ModulateAlpha`, or a translation only (8 dp); the default `fadeIn`, `fadeOut` and `Crossfade` are not used for whole surfaces (android-glass s9). **Hold-repeat** (stepper) changes in-memory state only, shows the total with no crossfade while repeating, and fires a haptic at most every 150 ms.

## 9. Compose, CSS and Tailwind mapping

Kotlin entry point: `AronTheme.colors / .type / .shapes / .stroke / .space / .size / .elevation / .motion / .glass`, each a `@ReadOnlyComposable` getter over a `CompositionLocal`. `AronTheme(language, mode = AronMode, tier = GlassTier, content)`; `AronMode` is `Light`, `Dark`, `Sunlight`. `LocalGlassTier` and `LocalReducedMotion` sit beside them.

| Token | Kotlin | CSS variable | Tailwind utility |
|---|---|---|---|
| `bg.gradient`, `bg.glow.a`, `bg.glow.b`, `bg.solid` | `colors.bgGradient` (stops 0, .5, 1), `bgGlowA`, `bgGlowB`, `bgSolid` | `--bg-gradient`, `--bg-glow-a`, `--bg-glow-b`, `--bg-solid` | `bg-page`, `bg-solid-page` |
| `surface.glass`, `.glass.strong`, **`.glass.field`**, `.solid`, `.solid.raised`, `.scrim` | `surfaceGlass`, `surfaceGlassStrong`, `surfaceGlassField`, `surfaceSolid`, `surfaceSolidRaised`, `surfaceScrim` | `--surface-glass`, `--surface-glass-strong`, `--surface-glass-field`, `--surface-solid`, `--surface-solid-raised`, `--surface-scrim` | `bg-glass`, `bg-glass-strong`, `bg-glass-field`, `bg-solid`, `bg-raised`, `bg-scrim` |
| `border.hairline` (+ `.top`, `.bottom`), `.divider`, `.solid`, `.input`, `.focus` | `borderHairline`, `borderHairlineTop`, `borderHairlineBottom`, `borderDivider`, `borderSolid`, `borderInput`, `borderFocus` | `--border-hairline` ... `--border-focus` | `border-hairline`, `border-divider`, `border-outline` (web), `border-input`, `outline-focus` (web) |
| `text.primary`, `.secondary`, `.disabled`, **`.placeholder`**, `.onAccent` | `textPrimary`, `textSecondary`, `textDisabled`, `textPlaceholder`, `textOnAccent` | `--text-primary` ... `--text-on-accent` | `text-primary`, `text-secondary`, `text-disabled`, `text-placeholder`, `text-on-accent` |
| `accent` (graphic), **`accent.text`**, `accent.hi`, **`accent.fill`**, `accent.pressed`, `accent.container`, `accent.onContainer` | `accent`, `accentText`, `accentHi`, `accentFill`, `accentPressed`, `accentContainer`, `accentOnContainer` | `--accent`, `--accent-text`, `--accent-hi`, `--accent-fill`, `--accent-pressed`, `--accent-container`, `--accent-on-container` | **`text-accent` = `--accent-text`**; `bg-accent-graphic`, `ring-accent-graphic`, `fill-accent-graphic` = `--accent`; `from-accent-hi to-accent-fill`; `bg-accent-container`, `text-accent-on-container` |
| `success`, `warning`, `danger`, `offline` (+ `.container`, `.onContainer`), **`danger.fill`** | `success`, `successContainer`, `successOnContainer` (same for the others), `dangerFill` | `--success`, `--success-container`, `--success-on-container` | `text-success` (icons only), `bg-success-container`, `text-success-on-container`, `bg-danger-fill` |
| `state.pressed`, `.disabled.fill`, `.disabled.label`, `.skeleton`, `.skeleton.sweep` | `statePressed`, `stateDisabledFill`, `stateDisabledLabel`, `stateSkeleton`, `stateSkeletonSweep` | `--state-pressed` ... | `bg-pressed`, `bg-disabled`, `text-disabled-label`, `bg-skeleton` |
| `radius.chip`, `.card`, `.sheet`, **`.well`**, `.full` | `shapes.chip`, `.card`, `.sheet`, `.well`, `.full` | `--radius-chip`, `--radius-card`, `--radius-sheet`, `--radius-well` | `rounded-chip`, `rounded-card`, `rounded-sheet`, `rounded-well`, `rounded-full` |
| `stroke.hairline`, `.strong`, `.focus` | `stroke.hairline`, `.strong`, `.focus` (Dp) | `--stroke-hairline` | `border`, `border-[1.5px]`, `ring-2` |
| `elev.page`, `.card`, `.sheet`, `.dialog` | `elevation.page`, `.card`, `.sheet`, `.dialog` (`AronShadow`) | `--elev-card`, `--elev-sheet`, `--elev-dialog` | `shadow-card`, `shadow-sheet`, `shadow-dialog` |
| `space.*`, `size.*` | `space.s1` to `s14`, `.screen`, `.gutter`, `.card`; `size.touch`, `.primary`, `.field`, `.segment`, `.chip`, `.icon`, `.thumb`, `.topBar`, `.bottomBar` | `--space-screen`, `--size-touch`, `--ctl-h` | `p-1` to `p-14`, `min-h-12`, `h-14` |
| `type.*` (+ `weight.semi`) | `type.display` ... `type.label` (`TextStyle`, language- and mode-aware) | `--type-display` ... | `text-display`, `text-numeral`, `text-title`, `text-heading`, `text-body`, `text-body-strong`, `text-caption`, `text-label` |
| `motion.*`, `ease.*` | `motion.press`, `.fast`, `.base`, `.sheetSpring`, `.reduced`; `motion.easeOut` ... | `--motion-fast` ... `--ease-out` | `duration-fast`, `duration-base`, `duration-sheet`, `ease-out-soft` |
| `glass.*` | `glass.blurBar`, `.blurSheet`, `.maxBlurLayers`, `.floor`, `.edgeFade` | `--glass-blur-bar` (web tier A only) | `backdrop-blur-bar` (tier A only) |

CSS rules for the web theme: `backdrop-filter` only on tier A, guarded by `@supports (backdrop-filter: blur(1px))` and `@media (prefers-reduced-transparency: no-preference)`; otherwise bars fall back to `surface.glass.strong` at 92%, then to `surface.solid`.

## 10. Open items (defaults taken, logged for DECISIONS.md)

1. **Number grouping conflict.** `LocaleDigits` groups the South Asian way (`12,34,567`) while `docs/20` T-0-121 says Western grouping. They agree below 1,00,000 (all single-memo amounts). The lead rules; `LocaleDigits` stays the single source.
2. **Accent is blue (docs/32), not the vendor's red-to-purple.** Revisit only if AKTCL names a brand colour (Q-UI-13).
3. **Weights.** Static 400 and 700 ship; the variable font (600 roles, sunlight 600 or 700) is gated by s7 and may be dropped.
4. **`TokenContrastTest` (kit lane).** JVM test asserting every row of s2.7 raw and glare from the token values; the web has the same test on `tokens.css`.
5. **Register `cfg.app.ui_glass`** (`auto`, `lite`, `off`) and **`cfg.app.ui_glass_min_ram_mb`** (default 3000) in `docs/19`; `off` and a higher floor are the restrictive direction.
6. **Reserved, not designed:** programme dots on outlet rows and any target, loyalty or discount surface (docs/27 deferred). Rows keep an empty leading slot.
7. **Primary fill: deep (default, passes docs/32 2a.4) or bright (needs a written amendment).** Owner decision, README decision 1.
8. **Secondary text hierarchy.** `text.secondary` is dark slate by gate, so hierarchy leans on size and weight. Owner may look at it in the preview.
9. **Latin face.** Android bundles Noto Sans Latin; the web spec uses the system UI face. One face for both (for example Inter, OFL) needs a font request. README decision 4.
10. **Money reading by TalkBack.** Spell amounts as "<amount> টাকা" without `৳` and group commas; verify with Google bn-BD TTS in the device lab (android-glass s6.3).

## 11. Web alignment (answer to the extension list in `web-glass.md`)

Role names and role colours are shared by Android and web; page-gradient stops, glass alphas and blur sizes are per-platform parameters. The web adopts the s2 values and re-runs its own pairs (`web-glass.md` s2.4); its page gradient is brighter, so its glass alphas may differ, its role colours may not. Web is office use: it keeps translucent cards and `surface.data` (docs/32 2a.1 allows full tier A glass there).

| web-glass name | v1 decision |
|---|---|
| `--accent` `#1D5FD1` / `#4F93FF`, `-hover`, `-ink`, `-wash`, `-on` | graphic `accent`; text `accent.text`; hover `accent.pressed`; wash `accent.container`; label `text.onAccent` |
| status `fill` / `ink` / `wash` | fill = role colour (icons, bars), ink = `*.onContainer` (all status text), wash = `*.container` (opaque); the web's `#0CA30C`, `#FAB219` fills are replaced; chart series colours stay in the dataviz palette |
| `text.secondary`, `text.primary` | the s2.4 values |
| `text.tertiary` | **rejected**: in dark, a third level at 4.5:1 on glass is indistinguishable from `text.secondary`; use `type.caption` in `text.secondary` |
| `surface.data` (92%), `glass.lite` (92%), `glass.strong` (78%) | `surface.glass.strong` with the tier alpha (92 percent for data and tier B); the web may keep 86 percent on tier A bars if its ledger holds |
| `surface.sunken`, `border.field`, `border.edge`, `focus-ring`, `scrim`, `page-base` | = `surface.solid.raised`, `border.input`, `border.hairline.bottom`, `border.focus`, `surface.scrim`, `bg.solid` |
| `--elev-1` to `-4` | = `elev.card`, (web-only raised level for menus), `elev.sheet`, `elev.dialog` |
| three-blob page gradient | web keeps three static blobs in a fixed layer; Android uses two glows |
| density, sheen and blur variables, `saturate(1.4)` | web-only platform parameters |
