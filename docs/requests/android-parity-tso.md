# Requests from lane android-parity-tso to the lead

1. **Final Submit: clear a route's data (old app: red trash icon on a route card with data).** The icon is drawn (D-PAR-TSO-03) but there is no clear endpoint in the contract or `FinalSubmitApi`. Need: what the old app's clear does (delete the day's memos of that route? reset it?), the endpoint, and its scope rule. Until then `FinalSubmitScreen(onClearRoute = null)` shows the icon with no action; nothing is faked.
2. **Online map images (My Team 28, Retailer 29).** Robolectric cannot render the Google map view, so the online state with markers, fullscreen and recentre has no recorded golden. Needs a device-lab check (D-UI-01 style) or a decision to record a fake map surface.
