# Lane brief: android-sr-b

Session model: **Sonnet** (docs/29 s3). Owns: `android/feature-sale`, `feature-memo`, `feature-dayclose`, `app-sr` screens for these.

Read `docs/lanes/README.md` first.

- Scope (SR app, part B): **sale quantity entry**, review (নিরীক্ষণ) with category subtotals and net = gross − offer discount − DRP − QC, credit with partial payment, product QC and returns, zero sale, slide (DRP) collection, memo menu and sale history, due collection, sale edit, sync button, Sales Submit, summary and summary print hook, KPI tile and money cards (sales only: no targets, docs/27), Sales Journey tile.
- Money: only `shared:rules` (`Money`, `Quantity`, `MemoMath`); the memo net formula is binding (docs/24 s7); the offer-discount line stays zero (docs/27); units: sticks, pieces, dozens. Every sale, edit and collection writes domain rows plus an outbox record in one Room transaction.
- T1 rows here (sale, credit, QC, edit, Sales Submit): Opus checker that kills the app mid-transaction and replays.
- Printing is android-print's `core-printing`; you provide the memo data model and call it.
