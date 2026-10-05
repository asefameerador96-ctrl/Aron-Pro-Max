# Browser setup: Google Maps keys, Firebase, GitHub secrets

Paste the block below into a **separate Claude chat that can control your browser**. It sets up what Aron needs from Google and stores the results in GitHub without any secret ever appearing in a chat or a file. Supervise it: it is signed in to your Google and GitHub accounts.

```
You are helping set up the Google and GitHub side of a software project called Aron. Work in my browser, one step at a time, and tell me what you are doing. You may only do the steps below. If anything asks for a payment card, a new billing account, a phone verification, or anything not listed, STOP and ask me.

SECRETS RULES (most important)
- Never type, paste, print, summarise or screenshot the VALUE of an API key, a private key, or the contents of a JSON key file in this chat. Show names only.
- Never write a secret into any file or into the code repository.
- Secrets go only into GitHub: repository https://github.com/asefameerador96-ctrl/Aron-Pro-Max, Settings > Secrets and variables > Actions > New repository secret.
- When you finish, show me the NAMES of the secrets you created, not their values.

PART 1. Google Cloud project
1. Open https://console.cloud.google.com and sign in as me. Use my existing project that has the paid Maps billing, or if there is none, create a project named "aktcl-aron" and link my existing billing account to it. Tell me which project you used and its Project ID.
2. APIs & Services > Library. Enable exactly these: "Maps SDK for Android", "Maps JavaScript API", "Geocoding API".
3. APIs & Services > Credentials > Create credentials > API key. Create TWO keys:
   a. Name "aron-maps-android". Edit the key: API restrictions = Restrict key = tick only "Maps SDK for Android". Application restrictions = None for now (I will give you the app package names and SHA-1 fingerprints later and you will add them).
   b. Name "aron-maps-web". API restrictions = tick only "Maps JavaScript API" and "Geocoding API". Application restrictions = Websites (HTTP referrers) = add exactly: http://localhost:3000/*   (I will give you the real website address later).
4. Billing > Budgets & alerts: create a budget for this project. Ask me for the monthly amount and my email before you save it. Alert at 50%, 90% and 100%.
5. APIs & Services > Enabled APIs > Maps JavaScript API > Quotas: tell me the current daily request limit; do not change it.

PART 2. Firebase (for push notifications)
6. Open https://console.firebase.google.com. Add project > choose the SAME Google Cloud project from Part 1 > turn Google Analytics OFF > create.
7. Project settings > General > Your apps > Add app > Android. Register THREE apps, one at a time:
   - Package name com.aktcl.aron.sr   nickname "Aron SR"
   - Package name com.aktcl.aron.amo  nickname "Aron AMO"
   - Package name com.aktcl.aron.tso  nickname "Aron TSO"
   Leave SHA-1 empty for now. Skip all the "add the SDK" steps.
8. Project settings > General: download google-services.json (it now contains all three apps). Tell me where the file was saved. Do NOT open it in this chat.
9. Project settings > Cloud Messaging: confirm "Firebase Cloud Messaging API (V1)" is enabled; if not, enable it.
10. Project settings > Service accounts > Generate new private key > confirm. A JSON file downloads. Tell me where it was saved. Do NOT open it in this chat.

PART 3. GitHub secrets (names must match exactly)
11. In the repository above, Settings > Environments: confirm an environment named "azure-dev" exists. If not, create it (no rules, no reviewers).
12. Settings > Secrets and variables > Actions > New repository secret. Create:
    - MAPS_ANDROID_KEY        = the value of key "aron-maps-android" (Credentials > the key > Show key; copy it straight into the GitHub box)
    - MAPS_WEB_KEY            = the value of key "aron-maps-web" (same way)
    - GOOGLE_SERVICES_JSON    = the full contents of the downloaded google-services.json
    - FCM_SERVICE_ACCOUNT_JSON = the full contents of the downloaded service account JSON
    For the two JSON files: if you cannot read local files, STOP and ask me to open each file in Notepad, copy everything, and paste it into the GitHub secret box myself while you wait. Do not ask me to paste the contents into the chat.
13. After saving, delete the two downloaded JSON files from my Downloads folder, or tell me to do it, and empty the recycle bin.

REPORT BACK (no secret values)
- Google Cloud Project ID and Firebase Project ID
- the names and API restrictions of the two Maps keys
- the budget amount and alert emails
- the daily Maps JavaScript quota you saw
- the list of GitHub secret names now present
- anything you could not do and why
```

## Later (I will tell you when)

- Add the **app restrictions** to the Android key: package names `com.aktcl.aron.sr`, `.amo`, `.tso` with the SHA-1 fingerprint of the signing key I generate.
- Add the **real website address** to the web key's referrers.
- Cap the Maps APIs' daily quota to a number you choose, so a bug cannot run up a bill.
