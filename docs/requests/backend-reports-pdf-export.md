# backend-reports → lead / infra: PDF export needs a library and a Bangla font (F-SYS-064)

Status: open, from backend-reports, 2026-10-15.

The export job (xlsx, `ExportJobService`) is built and tested. `format: pdf` is accepted by the report definitions but the job answers `failed` ("format pdf is not produced by the export job yet"). The server-rendered print view (`format: print`, HTML with an A4 landscape `@page`) works and prints from any browser.

What a real PDF needs, and why I did not pick it alone:
- A PDF library in the JVM (candidate: OpenPDF, LGPL/MPL, or Apache PDFBox) added to `gradle/libs.versions.toml`.
- Bangla shaping: a plain PDF writer shows Bangla conjuncts wrongly unless the library does complex-script shaping (PDFBox does not; OpenPDF needs the font bundled and an `iText`-style layout with ICU shaping). Bundled font: Noto Sans Bengali (OFL), about 300 KB.
- Alternative: render the print HTML to PDF with headless Chromium in the worker image (shapes Bangla correctly; adds about 150 MB to the worker image, which the infra lane owns).

Decision wanted: library or headless Chromium. Until then PDF is `failed` with a clear error and the print view covers the need. Logged as D-REP-06 in DECISIONS.md.
