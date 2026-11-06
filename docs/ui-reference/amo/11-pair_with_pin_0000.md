# 11. Pair with PIN 0000

## Screens in the image
- Left: Android (Samsung One UI, dark) system dialog "Bluetooth pairing request" over the dimmed Bluetooth settings screen, with the on-screen number keyboard open. System UI, not the vendor app.
- Right: Android Settings > Bluetooth after pairing: the printer RPP02N now under Paired devices. System UI, not the vendor app.
- Status bar: not shown.

## Layout, top to bottom
Left screen:
1. Dimmed background: Bluetooth top bar (back chevron, title "Bluetooth", "Scan" and three-dot menu, all greyed), the "On" bar with toggle (dimmed), the helper text "Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices." (dimmed).
2. Dialog: dark-grey rounded card.
   - Title (bold white): "Bluetooth pairing request".
   - Message: "Enter PIN to pair with RPP02N (Try 0000 or 1234)."
   - Field label "PIN"; input underlined in blue showing four masked characters "...." (four dots, blue cursor after them).
   - Two text buttons at the bottom separated by a thin divider: "Cancel" (left) and "Pair" (right), both white bold, no fill.
3. Samsung keyboard docked below: a top toolbar of round icons (emoji, settings gear, microphone, search, translate, three dots), number row 1 2 3 4 5 6 7 8 9 0, letter rows q-p, a-l, shift z-m backspace, bottom row "!#1", comma, space bar labelled "English (UK)", full stop, blue "Done" key; a keyboard-hide chevron and keyboard-grid icon at the bottom.

Right screen:
1. Bluetooth top bar: back chevron, title "Bluetooth", "Scan" (white), three-dot menu.
2. Dark bar with "On" (blue) and the toggle on.
3. Helper text (same three lines as before).
4. Section header "Paired devices"; one row: printer icon, "RPP02N", a thin vertical divider and a gear (settings) icon at the right.
5. Section header "Available devices"; three rows:
   - laptop icon, "28:D0:EA:1F:F0:DA" with grey sub-text "Device name will appear when this device is connected."
   - device icon (monitor with phone), "QCY H3-APP".
   - device icon, "oraimo SpaceBuds Neo+ BLE" (the plus sign is rendered "+" in the stylised font; read as "Neo+").
6. Gesture bar.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Bluetooth | Screen title | Top bar, both screens |
| Scan | Start scanning for devices | Top bar, both screens (greyed on left) |
| On | Bluetooth on | Both screens |
| Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices. | System hint | Both screens |
| Bluetooth pairing request | Dialog title | Left dialog |
| Enter PIN to pair with RPP02N (Try 0000 or 1234). | Prompt; default PINs 0000 or 1234 | Left dialog |
| PIN | Field label | Left dialog |
| .... (four masked characters) | PIN typed (0000) | Left dialog field |
| Cancel | Cancel pairing | Left dialog button |
| Pair | Confirm pairing | Left dialog button |
| English (UK) | Keyboard language | Space bar |
| !#1 | Switch to symbols | Keyboard |
| Done | Close keyboard | Keyboard, blue key |
| 1 2 3 4 5 6 7 8 9 0 | Keyboard number row | Keyboard |
| Paired devices | Section header | Right |
| RPP02N | Printer Bluetooth name | Right, paired row |
| Available devices | Section header | Right |
| 28:D0:EA:1F:F0:DA | A nearby device's MAC address | Right, available row 1 |
| Device name will appear when this device is connected. | System hint | Right, available row 1 |
| QCY H3-APP | Nearby device (earbuds) | Right, available row 2 |
| oraimo SpaceBuds Neo+ BLE | Nearby device (earbuds) | Right, available row 3 |

No Bangla text appears in this image.

## Data and figures shown
- PIN shown masked (four characters); the dialog text offers 0000 or 1234 (the manual says enter 0000 or 1234). Other Bluetooth devices around are unrelated to the app.

## Colours and style
- Dark system theme, black background, dark-grey dialog and rows, white text, blue underline and blue "On"/"Done". Entirely Android system UI.

## Navigation and behaviour
- Per the manual: enter PIN 0000 (or 1234) and tap Pair. On success the printer moves from Available devices to Paired devices (right screen).
- Cancel aborts pairing. The gear icon on the paired row opens that device's settings (not described).

## Differences between the image and the manual text
- The manual says only "Left: pairing request asking for a PIN. Right: the printer now shows under Paired devices." The image additionally shows the keyboard, other nearby devices (a MAC address, QCY H3-APP, oraimo SpaceBuds Neo+ BLE) and the gear icon beside the paired printer.

## Open questions for the owner
- None.
