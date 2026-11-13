# Requests from the SR parity lanes (P-SR2 section)

## P-SR2-R1: host bar titles for the vendor QC and Slide pages (P-SR1 shell)
`SaleRoute(vendorQc = true)` shows the vendor's Product QC and Slide pages. The host's top bar must read "QC" on the QC tiles, "QC এন্ট্রি" on the QC entry page and "স্লাইড" on the Slide. `SaleRoute(onQcEntryPage: (Boolean) -> Unit)` reports when the entry page is open; the Slide stage is already known to the host. Back is handled inside the route (entry page: same as বাতিল; QC tiles: the old Cancel).

## P-SR2-R2: owner question, KPI Dashboard and Brand wise KPI routing
`ProgrammeHook.KPI` and `ProgrammeHook.BRAND_KPI` pages exist (what the earlier app shows, "figures arrive later", no number). Nothing opens them yet: the SR's KPI tile opens the existing KPI strip with real figures. Question: should the KPI tile open the vendor-style KPI Dashboard hook, with "See all brands" opening Brand wise KPI? (Standing rule 2: target and KPI figures stay deferred until the owner says so.)

## P-SR2-R3: Collect a due (no contract change)
The one-tap "পরিশোধিত করুন" writes ONE `due_collection` for the whole outstanding of the memo (`DueLedger.markPaid`), so the full settlement is already one record. No server or contract change is needed.
