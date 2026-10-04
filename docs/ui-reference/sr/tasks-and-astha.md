# SR App — Task and Astha menus (reference)

Images: `tasks.png`, `astha.png`. Manual references and existing gaps: G-man-049 (task status labels), G-man-045 and G-man-047 (Astha views, gift choice); `docs/06` Task Delegation and Astha, `docs/07` AMO Task Delegation.

## Statements by AKTCL (2026-10-05)

> **Task:** if the AMO assigns any task from his app, it shows up here in the SR's app, **while giving an app notification on the SR's device**.
> **Astha** is the loyalty programme; the details will be shared later.

## Task screen

| Element | Shown | Reading |
|---|---|---|
| Title | `টাস্ক` | Task (the Home tile says `টাস্ক ডেলিগেশন`, Task Delegation) |
| Empty state | `আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।` | "Your AMO has not assigned any task." |

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-43 | AKTCL states that a new task **sends a notification to the SR's phone**. Neither the spec nor any manual mentions push notifications (the AMO inventory lists "notifications and what drives the red badges" as unknown). | New requirement. Design: a push message (Firebase Cloud Messaging; Azure Notification Hubs is in the vendored Azure skills) is only a **nudge**; the task itself comes down with the next sync, so a missed or delayed push never loses a task, and offline phones see it as soon as they sync. Push has no polling, so it respects the battery budget (R4). Needs: a device token table, a notification channel with Bangla text, admin switches (`cfg.notify.*`), and a decision on **which events notify** (task assigned/resolved, new app version, config change, day reminders). `MUST-CONFIRM`. | docs/16, 17, 18, 19 |
| UI-SR-44 | The empty state names the AMO. | The task list is filtered to tasks assigned to this SR (by AMO or TSO, `docs/06`). Keep the empty state; add a "synced at <time>" line so an empty list is not mistaken for a stale one. | docs/17 |

## Astha menu

| Element | Shown | Reading |
|---|---|---|
| Title | `আস্থা` | Astha |
| Tile 1 | Astha emblem, `টার্গেট` | Target |
| Tile 2 | gift-box icon, `গিফট নির্বাচন।` | Gift selection (the label ends with a stray `।`) |

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-45 | Two entries: **Target** and **Gift selection**. The spec describes tabs (Route info / Shop info) and a gift hand-over photo under Photo Capture. | Gift selection is the SR's view of the gift each Astha outlet chose on the web (G-man-047). Wait for AKTCL's Astha detail before defining fields, rules and photo steps. The Astha emblem on this tile is also the fourth dot in the outlet list (UI-SR-22). | docs/15, 10 |
| UI-SR-46 | A stray full stop on one label. | Fix in the string catalogue. | docs/17 |
