# UI reference

Screenshots of the current Aron apps and dashboard, supplied by AKTCL, with one note file per screen or flow. They are the visual ground truth for "same workflow, screen for screen" (`CLAUDE.md`, `docs/01`). They complement the four user manuals, which are the source for the full screen/field/rule inventory.

## Layout and naming

```
docs/ui-reference/
  sr/    <nn>-<screen>.webp        + <screen>.md
  amo/   ...
  tso/   ...
  web/   ...
```

Each note file contains: what the screen shows (labels in Bangla with an English reading), a table of fields/controls, any figures decoded and arithmetic-checked, differences from the spec (`UI-<ROLE>-nn` IDs), and the consequence for data, config and offline behaviour.

## Index

| Screen | Files | Findings |
|---|---|---|
| SR home | `sr/home.md`, `sr/home-1-tiles.webp`, `sr/home-2-kpis.webp` | UI-SR-01 to UI-SR-10 |
| SR attendance | `sr/attendance.md`, `sr/attendance.png` | UI-SR-11 to UI-SR-14 |
| SR stock | `sr/stock.md`, `sr/stock.webp` | UI-SR-15 to UI-SR-20 |
| SR sale: choose retailer | `sr/sale-select.md`, `sr/sale-select.png` | UI-SR-21 to UI-SR-25 |
| SR memo | `sr/memo.md` (image held back, see note) | UI-SR-26 to UI-SR-29 |

## Rules for this folder

- Two captures (the open outlet list and the memo view) show outlet phone numbers and are **not stored**; their content is described in `sr/sale-select.md` and `sr/memo.md`. Send a redacted copy if the pixels matter.
- Test accounts only. If a screenshot shows a real rep, outlet owner or phone number, crop or blur it before it is added.
- Do not copy artwork (icons, logos) into the new app without confirming AKTCL owns it.
- Each note compares against the existing spec and the manual inventory, so a new feature surfaces as a gap rather than being silently absorbed.
