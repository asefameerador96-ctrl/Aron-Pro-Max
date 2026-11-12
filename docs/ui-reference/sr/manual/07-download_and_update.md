# 07. Download and update

## Screens in the image
Two phone screenshots side by side of the in-app update screen. Left: download in progress (24 percent). Right: the same screen dimmed with the Android dialog "ARON SR - Do you want to update this app?" after the download completed.

## Layout, top to bottom
Left screen:
1. White top strip: "Network Status", green dot, "অনলাইন" (right-aligned; no back chevron here).
2. Light grey page.
3. Orange "NEW UPDATE" ribbon banner.
4. Purple cloud with a download arrow.
5. "Update Available" heading, version line, change summary (two lines).
6. A thin progress bar: red fill for the first quarter on a light grey track.
7. "Downloading 24%" centred under the bar.
8. A warning line with an info icon in red, serif-style type.

Right screen: the same page dimmed. Over it, a dark rounded dialog (app icon, "ARON SR", question, two buttons). The bar is fully filled in dark red; text beneath reads "Download completed"; the red warning line stays.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Network Status | Connectivity label | Top strip, both |
| অনলাইন | Online | Top strip, green, both |
| NEW UPDATE | Banner | Both |
| Update Available | Heading | Both |
| Version 1.0.25 available | Offered version | Both |
| New promotion modality., Login time 2FA verification., Sync file update without deleting. | Release notes (as in screenshot 06) | Both |
| Downloading 24% | Progress text | Left |
| Download completed | Progress text when done | Right |
| Please do not close the app while downloading | Warning (info icon in front, red) | Both |
| ARON SR | App name | Right, dialog title |
| Do you want to update this app? | Android update confirmation | Right, dialog |
| Cancel | Cancel | Dialog, left button |
| Update | Install the update | Dialog, right button |

## Data and figures shown
- Download progress 24 percent (left), then 100 percent and "Download completed" (right).
- Version 1.0.25 is the offered build.
- The online status stays green throughout.

## Colours and style
- Light grey page, orange banner, purple cloud, same as screenshot 06.
- Progress bar: red (about #D9453F) on light grey; dark red when dimmed.
- Warning text: red, serif-style face; the rest is the system sans-serif.
- Dialog: Android system dark dialog with white bold buttons.
- The red "Download App & Install" button of screenshot 06 is gone; the progress bar replaces it.

## Navigation and behaviour
- After the user taps "Download App & Install" the file downloads inside the app with a live percentage; the app must stay open and online.
- When the download completes, the Android installer dialog asks "Do you want to update this app?"; Update installs over the existing app, then the manual says to tap Open.
- Cancel dismisses the installer; the downloaded file remains.
- There is no pause or cancel control for the download itself.

## Differences between the image and the manual text
- The manual matches. The manual does not mention "Download completed" or the 24 percent example.
- The image shows both states of the same page (left in progress, right completed), while the manual lists them as left and right items.
- The in-app warning in the image is "Please do not close the app while downloading" (manual quotes it).

## Open questions for the owner
- Does the rebuild keep a self-download update path (needed outside Play), or does MDM/device-owner push updates? The manual says "Keep internet on" while downloading; a resumable download would help on weak networks.
