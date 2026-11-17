# Lane P-SR1 (android-parity-sr-1): SR parity, first half of the journey

Branch `lane/android-parity-sr-1` from INT 4e064555. Nothing here was walked on a device; the lane CI Android job is the proof.

## Shell slice (D-PAR-SR1-01)

Finding: the white top bar, "✓ ১০:৪২" chip and pastel page in the audit come from the TEST frames `Frame()` in `SrStateScreenshotTests` / `ScreenshotTests`, which used the legacy kit `AronTopBar` / `AronScreen` (glass bar, gradient page). Production SR hosts (`SrApp`) already draw `aron.AronScaffold` with the gradient bar. Fix once in the kit:

- `core-ui/Screen.kt` `AronTopBar` delegates to the gradient `aron.AronTopBar` (new optional `subtitle`), `AronScreen` paints the look's flat `page`.
- The two test frames lose the chip, so the recorded images show the production chrome.

## Audit table (SR, my screens)

| Finding | Decision | Commit | Image |
|---|---|---|---|
| Gradient bar / flat grey page on every SR screen | FIX (kit) | see git log | stock_bn_empty etc. |

(Rows for the remaining screens are added slice by slice.)

## Not run
Device walk, Android CI job (until its run id is reported), nothing on a real printer.
