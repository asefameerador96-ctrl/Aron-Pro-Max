# SR App — Attendance (reference)

Image: `attendance.png`. Test account `sr334001`, route "Apsis RouteDaily", 2026-10-05, before check-in. Manual references and existing gaps: G-man-029 (attendance UX), `docs/06` day-flow step 1.

## What the screen shows

| Element | Shown | Reading |
|---|---|---|
| Title bar | back arrow, calendar-clock icon, `অ্যাটেনডেন্স` | Attendance |
| User card | `SR - Testing Banani (sr334001)`, `Apsis RouteDaily, 2026-10-05` | same header as Home |
| Location card | pin icon, address text, red circular-arrow icon | current position as text, with a refresh control |
| Address text | `RC38+J3Q, RC38+J3Q, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh` | a reverse-geocoded address; the Plus Code `RC38+J3Q` appears twice (as name and as street) |
| Message | `আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।` | "You have not checked in yet. Please check in before starting work." |
| Left button | green, login icon, `চেক ইন` | Check in (active) |
| Right button | grey, logout icon, `চেক আউট` | Check out (disabled look: not yet checked in, and before 17:00) |

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-11 | The address is **reverse-geocoded**, a network lookup. The Plus Code printed twice shows the geocoder returned a code instead of a street. | Attendance must not depend on it. Store latitude, longitude and accuracy at check-in; resolve a text address only when online and only for display. Offline, show the coordinates. This also saves data and battery (R4). | docs/17, 05 |
| UI-SR-12 | The refresh icon re-samples the location. | Fits the "single on-demand fix" rule (`docs/04`); keep, with a timeout and an accuracy shown. | docs/05 |
| UI-SR-13 | The message tells the SR to check in "before starting work". | `MUST-CONFIRM` whether Stock, Sale and the other tiles are blocked until check-in, or this is only a prompt (G-man-029). Recommendation: prompt, not block, because a block would stop a sale when a fix is slow; make it `cfg.day.require_checkin_before_sale`. | docs/19 |
| UI-SR-14 | Check-out is greyed before check-in. | Consistent with `docs/06` (check-out from 17:00). The enabling rule stays a config (`cfg.day.checkout_earliest_time`). | docs/19 |
