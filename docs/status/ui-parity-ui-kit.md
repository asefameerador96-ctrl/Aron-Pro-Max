# UI parity: lane ui-kit (android/core-ui)

Reference: docs/ui-reference/PARITY-SPEC.md s1 and s2, images in docs/ui-reference/sr/. Kit API: top of docs/status/android-core-ui.md.
Evidence: docs/evidence/ui-parity/kit-gallery-bn.png (and -sunlight). Side-by-side per group follows with the icon push.

| Component | Reference | Status | sha |
|---|---|---|---|
| Tokens (`AronLook`, `LocalAronLook`) | PARITY-SPEC s1 | DONE (exact values; Sunlight optional) | kit skeleton |
| AronTopBar + AronPrinterBadge | stock.webp, sale-select.png, summary.png | PARTIAL: drawn printer glyph; screen icon needs the vector set | kit skeleton |
| AronScaffold | stock.webp | DONE | kit skeleton |
| AronHomeHeader | home-1-tiles.webp | DONE (drawn gear and avatar) | kit skeleton |
| AronTile + AronTileGrid | home-1-tiles.webp, outlet-menu.png | PARTIAL: icons pending (placeholder square) | kit skeleton |
| PackCard + PackListHeader | stock.webp | DONE; packshot 56 x 90 dp (measured 51 x 83 on the screenshot; spec text says about 68 x 120, screenshot wins) | kit skeleton |
| PackshotImage + PackshotProvider | stock.webp | DONE (silhouette fallback per category); real images: ui-packshots lane | kit skeleton |
| TotalsPanel | stock.webp | DONE | kit skeleton |
| AronPrimaryButton / AronSecondaryButton / AronPlus / AronMinus | stock.webp, summary.png | DONE | kit skeleton |
| RetailerPicker + LetterFilterBar | sale-select.png | DONE | kit skeleton |
| SummaryTable | summary.png | DONE | kit skeleton |
| Icon set (12 home, 4 outlet menu, shared glyphs) | home-1-tiles.webp, outlet-menu.png | NOT STARTED | |
