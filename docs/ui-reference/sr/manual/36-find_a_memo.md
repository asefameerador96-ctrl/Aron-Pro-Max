# 36. Find a memo

## Screens in the image
Two phone screens (with Android status bar and three-button navigation bar). Left: the outlet picker list of today's memos (opened from the dropdown). Right: the মেমো (Memo) screen for the selected outlet with প্রিন্ট and এডিট.

## Layout, top to bottom
Left screen:
1. Status bar over the gradient: "3:12", a screenshot icon, location pin, "4G", two signal bars, battery "67" in a white pill.
2. The top bar is mostly hidden by the list sheet (a title icon is barely visible).
3. A white-pink (about #FFF7FF) list sheet. First row is highlighted grey: a small round brown logo, a blue dot, then outlet text and amount. Each following row: a coloured dot (blue or yellow/orange), outlet text in green (about #1B6B3A), a colon and the amount with "৳" at the right in green. Font sizes vary slightly between rows. A grey scroll bar at the right.
4. Android navigation bar at the bottom (three lines, circle, back).

Right screen:
1. Status bar as above; top bar gradient with white back chevron, a small coloured memo icon and the title "মেমো".
2. A white bordered card (top cut off by the bar) with a round white circle at its top-right holding a green printer icon.
3. A white rounded selector showing the chosen outlet (brown logo, blue dot, text, amount) and a dark drop-down arrow.
4. A dark-grey (about #4A4A4A) rounded alphabet filter bar: "All" in a purple gradient chip, then letters on two lines.
5. Memo table: header with red-to-purple vertical gradient, three white titles; one white row; a light-grey subtotal row.
6. A dark tab labelled "ডিসকাউন্ট" above a dark-grey header table (three titles), one white row and a "মোট" total row.
7. Three summary lines in purple text with values at the right.
8. Two buttons at the bottom: a red-to-purple gradient button "প্রিন্ট" with a printer icon; an amber/orange (about #FFB300 to #F59A00) button "এডিট" with a pencil icon.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:12 | Phone clock | Status bar |
| 67 | Battery | Status bar |
| Milon Store (2663519-01610888807-Sahabuddin Medicle Road,113) : ৬৫.০০ ৳ | Outlet, code-phone-address, memo value 65.00 taka | List row 1 (highlighted); also the selector on the right |
| Jahangir Tea Store (3700929-123-111 No Road) : ৬৫.০০ ৳ | Outlet and memo value | List row |
| Rubel Store (3700911-1336046649-Road No 108) : ৬৫.০০ ৳ | same | List row |
| Nazrul Veraitis Store (3700909-1813064900-108 No Road) : ৬৫.০০ ৳ | same | List row |
| Glosari Shop (3700906-123-Road No 108,House 14) : ১৯৫.০০ ৳ | same | List row |
| Bismillah store (3700875-01316372023-Lake View Sight) : ৬৫.০০ ৳ | same | List row |
| Hanif Store (3700849-1613321020-Agora Road) : ৪৬৫.০০ ৳ | same | List row |
| Rakib Store (3700848-1827506804-Agora Road) : ৬৫.০০ ৳ | same | List row |
| Nuzrul Store (3700846-01736107567-Agora Road) : ৭২০.০০ ৳ | same | List row |
| Rajon Store (3700837-1630354215-Agora Road) : ৪৬৫.০০ ৳ | same | List row |
| Sohel Tea Store (3700833-1879887729-Agora Road) : ৪৬৫.০০ ৳ | same | List row |
| Bachchu Store (3700739-01722343450-Agora Road) : ৪৬৫.০০ ৳ | same | List row |
| Kuddus Store (3700734-123-Agora Road) : ৪৬৫.০০ ৳ | same | List row |
| Giyas Uddin Store (3700701-01875302103-109 No Road) : ১৯৫.০০ ৳ | same | List row |
| Kamal Store (3700693-123-Pink City) : ৬৫.০০ ৳ | same | List row |
| Kuddus Store (3700683-1615286745-Pink City) : ৬৫.০০ ৳ | same | Last row, cut off at the bottom |
| মেমো | Memo | Right top bar |
| All A B F G H I J K L M N O R S V Y | Letter filter | Right filter bar |
| এসকেইউ | SKU | Memo table header |
| পরিমাণ | Quantity | Memo table header |
| দাম | Price | Memo table header |
| BDDF-10s | SKU | Memo row |
| ১০ | Quantity 10 | Memo row |
| ৬৫.০০ | Price 65.00 | Memo row |
| মোট সিগারেট | Total cigarettes | Subtotal row: ১০ and ৬৫.০০ |
| ডিসকাউন্ট | Discount | Dark tab |
| এসকেইউ / পরিমাণ / মূল্য | SKU / Quantity / Value | Discount table header |
| BDDF-10s | SKU | Discount row: quantity ০, value ১৩.০০ |
| মোট | Total | Discount total row: ০ and ১৩.০০ |
| মোট ডিসকাউন্ট | Total discount | - ১৩.০০ |
| মোট QC | Total QC | - ০.০০ |
| সর্বমোট | Grand total | ৫২.০০ |
| প্রিন্ট | Print | Button |
| এডিট | Edit | Button |

The digits in the long list are small; I read them as given but some (for example ৪৬৫ and ৭২০) carry some uncertainty. Phone numbers inside the outlet text are as printed.

## Data and figures shown
- The list shows about 16 outlets sold today, each with the memo value in taka, Bangla digits and "৳" after the number. Values seen: 65, 195, 465, 720.
- Selected memo (Milon Store): BDDF-10s, quantity 10, price 65.00. Discount block: 13.00. Total discount -13.00, QC -0.00, grand total 52.00 (65 minus 13).
- Quantity 10 for ৳65.00 equals one 10s pack of BDDF, so the quantity column here appears to be in sticks, not packets (inference).
- Coloured dots: blue or orange/yellow per outlet; their meaning is not stated. Milon Store has an extra small brown round logo (looks like the Astha logo, uncertain).

## Colours and style
- Red-to-purple gradient top bar. List sheet near white with pink tint; outlet text green. Alphabet bar dark grey with white letters; selected chip purple gradient. Table headers: memo table red-to-purple vertical gradient; discount table dark charcoal. Totals text purple (about #4A1A9A). Print: red-to-purple gradient; Edit: amber gradient. Printer circle: green icon on white.
- No packshots on the memo screen.

## Navigation and behaviour
- Manual: left, the memo list: outlets sold today with the memo value in taka; right, the memo with SKU, quantity, price, discount, QC and grand total, with প্রিন্ট and এডিট. The SR chooses the outlet, then taps প্রিন্ট to reprint or এডিট to edit.
- The letter bar filters the SKU rows by first letter (inferred). The green printer at the top prints the memo too (inferred).

## Differences between the image and the manual text
- The manual says the memo shows "discount"; the image shows a separate dark "ডিসকাউন্ট" table (BDDF-10s, 13.00) and "মোট ডিসকাউন্ট -১৩.০০". The owner has deferred discounts and the memo's discount line is meant to stay zero.
- The manual's list does not mention the coloured dots or the small logo.
- The memo shows a quantity of 10 at ৳65, not a packet count.

## Open questions for the owner
- What do the blue and yellow dots on outlets mean (for example memo state, printed or not, or outlet type)?
- What is the brown round logo on the first row?
- Should the discount block be hidden, or shown at zero, in the rebuild (standing rule 2)?
