# 16. Pair with PIN

## Screens in the image
Two phone screens side by side, Android system Bluetooth settings (dark theme, handwriting-style font), not the ARON app. Left: the "Bluetooth pairing request" dialog over the page, with the on-screen keyboard open. Right: the result, the printer now listed under "Paired devices".

## Layout, top to bottom
Left screen:
1. Background page dimmed: top bar (back chevron, "Bluetooth" in grey, "Scan", three-dot menu), a bar "On" with a blue toggle, and the grey hint paragraph.
2. A rounded dark dialog in the middle: bold title "Bluetooth pairing request", a line "Enter PIN to pair with RPP02N (Try 0000 or 1234).", a small label "PIN" above an input line holding four masked dots "••••" with a blue cursor and a blue underline; two text buttons "Cancel" and "Pair" divided by a thin vertical line.
3. The Samsung on-screen keyboard (dark): a toolbar of icons (emoji, settings gear, microphone, search, translate, three dots), a number row 1 to 0, letter rows q-p, a-l, z-m with shift and backspace, a bottom row "!#1", comma, space bar labelled "English (UK)", full stop, and a blue "Done" key; below it a keyboard-switch icon and a collapse chevron.
Right screen:
1. Top bar: back chevron, "Bluetooth", "Scan" (white), three-dot menu.
2. Bar "On" (blue) with a blue toggle; the grey hint paragraph.
3. Section "Paired devices": one dark rounded row with a printer icon, "RPP02N", a thin vertical divider and a grey gear (settings) icon.
4. Section "Available devices": a dark rounded block with three rows:
   - a laptop-like icon, "28:D0:EA:1F:F0:DA" and the small grey text "Device name will appear when this device is connected.";
   - a phone-and-computer icon and "QCY H3-APP";
   - the same icon and "oraimo SpaceBuds Neo+ BLE" (the last characters could be "Neo+" or "Neo t"; uncertain).
5. Thin home-indicator bar at the bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Bluetooth | Screen title | Top bar, both screens |
| Scan | Start a new scan | Top bar action, both screens |
| On | Bluetooth switched on | Switch bar |
| Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices. | System hint | Both screens |
| Bluetooth pairing request | Dialog title | Left screen |
| Enter PIN to pair with RPP02N (Try 0000 or 1234). | Instruction; the printer's PIN is 0000 or 1234 | Left dialog |
| PIN | Field label | Left dialog |
| •••• | Four masked PIN characters (the typed PIN) | Left dialog input |
| Cancel | Cancel | Left dialog |
| Pair | Pair | Left dialog |
| English (UK) | Keyboard language (on the space bar) | Left keyboard |
| Done | Keyboard action key | Left keyboard |
| Paired devices | Heading for paired devices | Right screen |
| RPP02N | The paired printer | Right screen, paired row |
| Available devices | Heading for found devices | Right screen |
| 28:D0:EA:1F:F0:DA | A nearby device shown by its Bluetooth address | Right screen |
| Device name will appear when this device is connected. | System note for an unnamed device | Right screen |
| QCY H3-APP | A nearby device (name as shown) | Right screen |
| oraimo SpaceBuds Neo+ BLE | A nearby device (earbuds; "+" uncertain) | Right screen |

## Data and figures shown
- The system prompt itself suggests the PIN "0000 or 1234"; the entered PIN is four dots (consistent with 0000 or 1234).
- After pairing, RPP02N moves from "Available devices" to "Paired devices", with a gear for its settings.
- Other nearby Bluetooth devices appear in the list (a bare address, QCY H3-APP, oraimo SpaceBuds). The SR must pick the printer's name.

## Colours and style
- Stock Android/Samsung dark theme: black background, dark grey rounded cards, white text, blue accent, handwriting-style font. No ARON look.
- The left dialog dims the page behind it; the numeric row of the keyboard is used for the PIN.

## Navigation and behaviour
- Tapping the printer in the list opens the pairing dialog; enter the PIN (0000 or 1234) and tap Pair.
- Pairing is done once per phone and printer; afterwards the printer shows under "Paired devices" and can be chosen in the app (screenshot 17).
- "Cancel" aborts; "Scan" re-scans.

## Differences between the image and the manual text
- The manual says "Pairing request: enter PIN 0000 (or 1234). Then the printer appears under Paired devices": matches. The dialog text itself reads "Try 0000 or 1234".
- The manual does not mention the other nearby devices in "Available devices", or that unnamed devices show only an address.
- The image is in English on a dark theme; on a Bangla phone the wording will differ.

## Open questions for the owner
- Do other printer models or batches use a different PIN than 0000 or 1234 (the dialog only says "Try")?
- Should the new app guide the rep through pairing in its own screens (with Bangla text), or keep this to the Android system dialog?
