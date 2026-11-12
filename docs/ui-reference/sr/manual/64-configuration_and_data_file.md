# 64. Configuration and data file

## Screens in the image
Two phone screens side by side, both the Settings page dimmed under a dialog. Left: "Configuration updated Successfully!" with a message that the app will close. Right: "সফল" with "ডাটা ফাইল পাঠানো হয়েছে।" (data file sent).

## Layout, top to bottom
1. Status bar over the (dimmed) gradient: 3:25 (left), 3:25 with a screenshot icon (right), location pin, 4G, two signal bars, battery 65.
2. Top bar, red-to-purple gradient dimmed to dark red-brown to dark purple; faint white back chevron and title "সেটিং".
3. Row 1 of three pale tiles (dimmed): language tile (flag icon, "বাংলা" and a down arrow), "PDA টু সাপোর্ট" (globe-and-monitor icon), "লগ আউট" (purple sign-out icon).
4. Row 2, one tile at the left (dimmed): a green round icon with a white gear and a down arrow; its label is cut off behind the dialog: left screen reads "Upd" and the right screen "Updat" followed by a hidden part (not readable; the visible start is "Upd", so the label is likely "Update Configuration" or similar).
5. Dark grey overlay over the whole page.
6. A white rounded dialog (about 28 dp radius, big shadow), centred:
   - Left: green circle with white check; blue bold heading "Configuration updated Successfully!"; black body text in three lines; a green rounded button "ওকে" at the lower right.
   - Right: green circle with white check; blue bold Bangla heading "সফল"; black Bangla body "ডাটা ফাইল পাঠানো হয়েছে।"; a green rounded button "ওকে" at the lower right.
7. Light grey Android navigation bar at the bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| সেটিং | Settings | Top bar (both) |
| বাংলা | Bangla (current language) | Language tile (both) |
| PDA টু সাপোর্ট | PDA to Support | Tile (both) |
| লগ আউট | Log Out | Tile (both) |
| Upd / Updat (rest hidden) | Start of the fourth tile label, unreadable beyond these letters | Row 2 tile 1 |
| Configuration updated Successfully! | Heading | Left dialog |
| After clicking on OK, your app will be closed. Please reopen your app and continue your daily operation. | Body text | Left dialog |
| ওকে | OK | Left and right dialogs |
| সফল | Success | Right dialog heading |
| ডাটা ফাইল পাঠানো হয়েছে। | Data file has been sent. | Right dialog body |

Status bar: 3:25, battery 65.

## Data and figures shown
- No figures. The two dialogs are confirmation states of two Settings actions: the (cut-off) update-configuration tile and the PDA টু সাপোর্ট tile.
- Current language shown on the language tile: বাংলা.

## Colours and style
- Dialog: white, 28 dp radius; heading blue (#1F6FB5) bold; body black; check icon green circle (#4CAF50) with white tick.
- OK button: green (#4CAF50), white bold Bangla label, about 8 dp radius, soft shadow.
- Background: dark grey overlay (about 50 % black); top bar dimmed.
- The English heading uses Roboto-like Latin; the right dialog uses the Bangla font.
- Fourth tile icon: green circle with a white gear and a down arrow, on a pale square.

## Navigation and behaviour
- Manual (screenshot 64): left: "Configuration updated successfully! After clicking OK, your app will be closed. Please reopen your app"; right: "সফল - ডাটা ফাইল পাঠানো হয়েছে" (data file sent). The SR taps PDA টু সাপোর্ট when support asks, and reopens the app after a configuration update.
- Tapping ওকে on the left dialog closes the app (the message says so); the SR must reopen it.
- Tapping ওকে on the right dialog just closes it (not stated; inferred from the button only).

## Differences between the image and the manual text
- The manual's quote ends at "Please reopen your app"; the image continues "and continue your daily operation."
- The manual's capitalisation "updated successfully!" differs from the image's "updated Successfully!".
- The fourth tile (update configuration) is shown in the image but its label is cut off and the manual (screenshot 63) does not list it.
- The image does not show which tile produced which dialog; the manual only implies it.

## Open questions for the owner
- What is the exact label of the fourth tile and what does it download or change?
- Does "Configuration updated" also happen automatically at login, or only from this tile?
- What is in the data file sent by PDA টু সাপোর্ট (local database, logs) and where does it go?
