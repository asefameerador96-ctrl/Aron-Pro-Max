# Request: absence count for the KPI dashboard warning (manual screen 60)

Lane android-sr-programmes (A-PROG3). Needed for the KPI Dashboard (SR manual screen 60). Not built on the phone until the bundle carries it; the screen is built WITHOUT the warning and the item is listed as not built.

## What the manual's screen shows

Screen 60 (KPI Dashboard) opens with a warning: an SR absent more than 2 days in a month is not considered for that month's KPI bonus. Next to it the month card shows the days (for example 10/30), the assigned KPI, the achieved KPI and its percentage.

## What the phone cannot do alone

The phone knows only its own attendance events, and only those it has recorded or downloaded for the current user. It does not know the days the SR was marked absent by the office or by a supervisor (approved leave, a corrected attendance, an admin mark), so a count from local data could say "not absent" when the server counts the SR absent, and the other way round. The phone must not invent this number.

## The field needed

A new optional member of `RouteSnapshot.kpi` (`RouteKpi`), additive for existing phones:

| Member | Type | Meaning |
|---|---|---|
| `absent_days` | integer, minimum 0, nullable | The number of working days of `month` up to and including `through_date` on which the SR of this route had no accepted check-in, by the same rule the KPI bonus uses. Null when the owner has not defined the rule. |
| `absent_limit_days` | integer, minimum 0, nullable | The limit above which the SR is not considered for the month's KPI bonus (2 in the manual). Null when not defined. |

The figures follow the figures rule of `RouteSnapshot`: the server figure through `through_date`, shown with that date, never added to or subtracted from by the phone. The phone may show its own check-in of today as the separate "today on this phone" line only.

## Where the server can derive it

From attendance (the check-in records of the SR for the route's working days, `calendar` working days of the month up to `through_date`) and the declared leave or admin attendance corrections, using the same working-day calendar that fills `RouteCalendar`. The owner needs to confirm the bonus rule (what counts as absent: no check-in, approved leave, a half day).

## Phone behaviour until it exists

Screen 60 is built without the warning. When `absent_days` and `absent_limit_days` are present and the days exceed the limit, the phone will show the manual's warning text (Bangla and English strings in the programmes resources); when they are null or absent nothing is shown (never an invented zero).
