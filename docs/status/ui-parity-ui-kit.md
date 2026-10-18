# UI parity: lane ui-kit (android/core-ui)

Reference: docs/ui-reference/PARITY-SPEC.md s1 and s2, images in docs/ui-reference/sr/. Kit API: top of docs/status/android-core-ui.md.
Evidence (left reference, right ours): docs/evidence/ui-parity/kit-home-tiles.png, kit-outlet-menu.png, kit-stock-packcard-totals.png, kit-retailer-letters.png, kit-summary-table.png; whole gallery kit-gallery-bn.png and -sunlight.

| Component | Reference | Status | sha |
|---|---|---|---|
| Tokens (`AronLook`, `LocalAronLook`) | PARITY-SPEC s1 | DONE (exact values; Sunlight optional) | kit skeleton |
| AronTopBar + AronPrinterBadge | stock.webp, sale-select.png, summary.png | PARTIAL: drawn printer glyph; screen icon needs the vector set | kit skeleton |
| AronScaffold | stock.webp | DONE | kit skeleton |
| AronHomeHeader | home-1-tiles.webp | DONE (drawn gear and avatar) | kit skeleton |
| AronTile + AronTileGrid | home-1-tiles.webp, outlet-menu.png | DONE (menu tiles 72 dp icon, red labels) | icons push |
| PackCard + PackListHeader | stock.webp | DONE; packshot 56 x 90 dp (measured 51 x 83 on the screenshot; spec text says about 68 x 120, screenshot wins) | kit skeleton |
| PackshotImage + PackshotProvider | stock.webp | DONE (silhouette fallback per category); real images: ui-packshots lane | kit skeleton |
| TotalsPanel | stock.webp | DONE | kit skeleton |
| AronPrimaryButton / AronSecondaryButton / AronPlus / AronMinus | stock.webp, summary.png | DONE | kit skeleton |
| RetailerPicker + LetterFilterBar | sale-select.png | DONE | kit skeleton |
| SummaryTable | summary.png | DONE | kit skeleton |
| Icons Home: attendance, stock, sale, memo, summary, sales_submit, outlet, tutorial, task_delegation, photo_capture, sales_journey, kpi (`R.drawable.aron_ic_*`) | home-1-tiles.webp | DONE as redrawn line art with the pink-purple-blue gradient; PARTIAL against the photo-real camera and the hands-with-stars art (Astha and loyalty tiles are deferred, docs/27: no icon) | icons push |
| Icons Outlet menu: outlet_new, outlet_close, outlet_edit, cluster | outlet-menu.png | DONE; PARTIAL: the close sign has an X, not the word CLOSE (no text in art: Bangla-first); cluster is pink-blue, the reference is flat blue | icons push |
| Shared: aron_ic_back, aron_ic_gear, aron_ic_printer, aron_ic_printer_off | stock.webp, home-1-tiles.webp | DONE (the kit itself draws these on Canvas; drawables are for screens) | icons push |
| Original art crops `aron_art_*` (16) | home-1-tiles.webp, outlet-menu.png | DONE: the owner's own icons (lead ruling), 3 to 12 KB each, 125 KB total WebP | art push |
| AronTopBar hanging printer badge, 22 sp title; tile labels medium weight | stock.webp, home-1-tiles.webp | DONE | art push |
| AronInfoCard, AronTargetCard | summary.png, home-1-tiles.webp | DONE (target bar hidden while targets are deferred) | art push |
| Sizes | APK budget | 19 vectors, 44 KB source total, largest 4.5 KB | icons push |
