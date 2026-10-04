# SR App — Outlet menu (reference)

Image: `outlet-menu.png`. Manual references and existing gaps: G-man-033 (forms pick cluster), G-man-017 (photo moves outlet), `docs/06` Outlet management, manual screen SR-S-46.

## Statement by AKTCL (2026-10-05)

> This is the edit / close / open / change-cluster option for an outlet.

## What the screen shows

| Tile | Label | Reading | In the manual (SR-S-46) | In `docs/06` |
|---|---|---|---|---|
| 1 | `নতুন দোকান` | New shop ("open") | yes | yes |
| 2 | `স্থায়ী বন্ধ` | Permanently closed | yes | yes |
| 3 | `তথ্য পরিবর্তন করুন` | Change information ("edit") | yes | yes |
| 4 | **`ক্লাস্টার`** | **Cluster** ("change cluster") | **no** (the manual shows three tiles) | **no** |

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-40 | A **fourth tile, Cluster**, exists in the live app. It is in neither the manual nor the spec. From AKTCL's wording it moves an outlet to a different cluster. | New feature, with unknowns: which fields (outlet, new cluster, reason, photo?), who verifies (AMO then web, as for the other three?), and whether the outlet's route changes with it. Model it as an outlet change request of a fourth type (`cluster`), and keep cluster history so past sales stay in the cluster they were made in. `MUST-CONFIRM`. | docs/15, 16 |
| UI-SR-41 | **The live app is newer than the manuals.** Three things now show up in the app that the manuals do not describe: Sales Journey and KPI tiles on Home (UI-SR-01/02) and this Cluster tile. | The manuals are not a complete inventory of the build the field uses. The rebuild must be checked against the live app, version by version. Ask AKTCL for the live app version and a walkthrough of **every** tile and menu (including admin-hidden ones), and treat each manual-derived "complete" claim as provisional. | docs/14 (risk), 15 |
| UI-SR-42 | The tile row order and icons follow the manual's three, then Cluster. | Parity: same four tiles in the same order. The artwork is the vendor's (see the README rule). | docs/06 |
