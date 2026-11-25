# Request: the category of each `by_pack` entry of the KPI (contract, additive)

Lane android-sr-programmes (A-PROG3), for the KPI Dashboard and Brand wise KPI (manual 60 and 61). Additive and optional for existing phones.

## The problem

`RouteKpi.brands[].by_pack` and `RouteKpi.categories[].by_pack` list `{pack_factor, target, achieved}` per pack size, in base units. The entries are NOT split by category: a brand row whose SKUs mix categories (for example a 20s cigarette SKU of 2,000 sticks and a match SKU of 30 dozen, `pack_factor` 1) has one by_pack list in which the sticks of the packets and the dozens of the matches sit side by side. The phone cannot tell which entry is which unit, and a base-unit sum of them (sticks plus dozens) is meaningless.

## What the phone does today, without guessing

The phone takes each brand's category from the product tree it already holds (`products.skus[].category_code` of the brand's SKUs). A brand whose SKUs are all cigarettes is shown in packets (each pack size over its own factor), all matches in dozens, all lighters in boxes. A brand that mixes categories, or whose category the phone cannot resolve, is shown with a dash and the note "unit not clear": its figures are never summed and never printed in a unit that may be wrong. The cigarette volume total is checked against the cigarette brands' packs only.

## The change asked for

Add an optional member to `KpiPackFigure` (the item of both `by_pack` lists):

| Member | Type | Meaning |
|---|---|---|
| `category_code` | string, optional (`cigarette`, `bidi`, `match`, `lighter`) | The category of the SKUs counted in this entry (the SKU `category_code`). |

With it the phone can show a mixed brand exactly: one line per category in that category's unit. Without it (older server) the phone behaves as above.

## Related question for the server lane

`AchievementRow.achieved_qty_base` is declared `minimum: 0`. The phone treats a negative value as invalid (a dash, never a crash); if the server can ever send one (a void netting below zero), say so and say what it means.
