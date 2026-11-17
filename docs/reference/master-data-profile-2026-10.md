# Master data profile, October 2026 (sanitised)

Source: the owner's workbook "Master Data - October 2026.xlsb" (51 MB, one sheet), shared on 2026-11-13 with the instruction
"map everything accordingly while developing the app". This file holds STRUCTURE AND COUNTS ONLY. The workbook carries owner
names and phone numbers of outlets and names and phones of sales reps: it is never committed, never copied into a test, a seed or
a log, and no row value appears here (the repository is public; standing rule 8 and docs/reports rule). The raw file lives only in
the lead's scratch folder and on the owner's Drive.

## Shape
One row per outlet: 737,159 rows, 32 columns.

| Column | Filled | Notes |
|---|---|---|
| Wing Code, Wing | all | 10 wings |
| Division Code, Division | all | 50 divisions, code 2 digits |
| Territory Code, Territory | all | 291 territories, numeric code |
| House Code, House | 644,198 | 292 houses (distribution houses), numeric code; empty on 92,961 rows |
| Zone Code, Zone | all | 1,049 zones, numeric code |
| Route Code, Route | all | 11,325 routes, numeric code |
| Visit Frequency, Visit Day | 648,682 | see below; empty on 88,477 rows |
| Sales_Rep_Id, Sales_Rep_Name, Phone_No | 644,198 / 644,092 / 550,278 | 6,954 reps; id is two lower-case letters then digits |
| Outlet Code | all | unique per row; six code shapes (below) |
| Outlet Name, Owner Name, Contact Number | all (nearly) | personal data |
| Address | 13 | practically unused |
| Cluster Name, Cluster Type | all | 104,876 clusters (zone + name) |
| Status | 644,198 | only value "Active"; empty on the 92,961 rows without house and rep |
| Latitude, Longitude | 729,884 | all inside Bangladesh; 7,275 rows have none; no zeros |
| Geo Classification | 592,895 | Rural, Urban, Semi Urban, Hill; empty on 144,264 |
| Channel | 644,198 | six values; empty on 92,961 |
| Sub-Channel | all but 1 | ten values |
| Created At, Updated At | all / 280,719 | text, not Excel dates |

## Hierarchy
Wing (10) > Division (50) > Territory (291) > Zone (1,049) > Route (11,325) > Outlet (737,159). House (292) sits beside the
geography: a house belongs to a territory and serves zones.

Checked parent links (how many children have more than one parent in the file):
- division in two wings: 0; territory in two divisions: 0; route in two zones: 0; outlet on two routes: 0; cluster name in two zones: n/a (keyed by zone).
- zone under two territories: 1; zone served by two houses: 1; house under two territories: 2. These four are data exceptions to list for the owner, not model features.
- a route with two reps: 7 routes. A rep with more than one route: 3,687 of 6,954 reps (1 route: 3,267; 2 routes: 3,020; 3 routes: 666; 4 routes: 1).

Wings by size:

| Wing | Outlets | Routes |
|---|---|---|
| Sylhet | 98,578 | 1,713 |
| Gaibandha | 95,898 | 1,452 |
| South Bengal | 88,729 | 1,323 |
| Rangpur | 79,324 | 1,199 |
| Dhaka South | 78,178 | 1,130 |
| Dhaka North | 77,578 | 1,052 |
| Noakhali | 61,649 | 936 |
| Chittagong South | 55,740 | 895 |
| Cumilla | 55,238 | 806 |
| Chittagong North | 46,247 | 819 |

Outlets per route: 1 to 251; most routes hold 50 to 99 (6,249 routes with 50 to 74, 2,615 with 75 to 99, 1,947 with 25 to 49,
333 with 100 to 124, 92 under 25, 89 with 125 or more).

## Visit plan
| Visit Frequency | Visit Day | Outlets |
|---|---|---|
| 6 | Daily | 229,279 |
| 3 | (Sat, Mon, Wed) | 168,220 |
| 3 | (Sun, Tue, Thu) | 168,042 |
| 2 | Sat, Tue | 28,095 |
| 2 | Sun, Wed | 27,619 |
| 2 | Mon, Thu | 27,347 |
| 3 | (Mon, Thu, Fri) | 43 |
| 3 | (Sat, Tue, Wed) | 37 |
| empty | empty | 88,477 |

Frequency is visits per week (6 = every selling day; Friday is the day off). The visit day is a property of the ROUTE in practice
(a rep with two or three routes alternates them by weekday). The two rare patterns include Friday or an odd pairing: list for the owner.

## Channels
| Channel | Sub-Channel | Outlets |
|---|---|---|
| GT Channel | GT | 539,807 |
| (empty) | GT | 88,774 |
| Astha Channel | Gold | 54,858 |
| RCC | RCC | 16,463 |
| DCC Channel | DCC | 11,600 |
| Astha Channel | Diamond | 10,920 |
| Astha Channel | Platinum | 8,092 |
| (empty) | DCC, RCC, Gold, HoReCa, MT, Diamond | 4,186 |
| Astha Channel | "--" | 1,201 |
| MT Channel | MT | 519 |
| HoReCa | HoReCa | 372 |
| Astha Channel | GT | 305 |
| Astha Channel | Titanium | 4 |
| mismatched pairs (GT/Gold, GT/RCC, RCC/Diamond, RCC/GT, GT/Platinum) | | 57 |

So: channel has six values (GT, Astha, RCC, DCC, MT, HoReCa) and the Astha tiers are Gold, Diamond, Platinum, Titanium, carried
in the sub-channel column. For the other channels the sub-channel repeats the channel.

## Clusters and geography class
Cluster type: Transit Hub 734,702; Residential 2,432; Commercial Hub 16; University Hub 6; Bazar Hub 2; Industrial Hub 1.
In effect the type is unset in the source ("Transit Hub" is the default). Geo classification: Rural 313,957; Urban 161,367;
Semi Urban 105,535; Hill 12,036; empty 144,264.

## Code shapes
- Wing code: 1 or 2 digits. Division code: 2 digits. Territory, zone, route codes: numeric, three digits or more. House code: numeric (1 to 3+ digits).
- Sales rep id: two lower-case letters followed by digits (all 644,198 filled rows).
- Outlet code, six shapes: digits only (589,247); digits + one capital + digits (129,036); digits + two capitals + digits (10,075);
  two capitals, a hyphen, digits (6,022); digits + capital + two digits (1,174); digits + several capitals + digits (836).
  The outlet code is a free-form business key: store it as text, never parse it.

## Data quality (counts only)
- 92,961 outlets have no house, no rep, no status and no channel (unassigned routes: outlets exist, nobody sells there today).
- 88,477 outlets have no visit plan. 7,275 have no coordinates. 144,264 have no geo classification.
- Outlet phone: 271,692 in the 11-digit national form, 409,186 with the leading zero dropped (a number stored as a number),
  362 with the 880 prefix, about 56,000 unusable (three digits or fewer: 51,735; other lengths: 4,122). Normalise on import; never trust.
- Rep phone: empty on 186,881 rows; mostly stored without the leading zero.
- Outlet names in non-ASCII (Bangla) script: 150 rows only. Outlet names are Latin-script in the source.
- Created At / Updated At are text.

## What this means for the build (first reading; the gap analysis follows in docs/reference/master-data-mapping.md)
1. Fleet size is confirmed from data: 6,954 reps, 11,325 routes, 737,159 outlets, 104,876 clusters. A rep's phone carries one to three routes (50 to 250 outlets each).
2. "House" (distribution house) is a real level the model must carry: territory > house, zone served by a house.
3. Visit frequency and weekday pattern drive "today's route".
4. Channel and Astha tier come from here: Astha tier = sub-channel of Astha outlets (Gold, Diamond, Platinum, Titanium).
5. This file is NOT loaded into the test account: it is fleet-sized personal data, and the standing rules keep the test account at pilot size and data migration out of scope until the owner says otherwise.
