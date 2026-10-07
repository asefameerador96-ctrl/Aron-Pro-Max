# Bundled fonts (android/core-ui/src/main/res/font)

| File | Font | Source | Licence |
|---|---|---|---|
| `noto_sans_bengali_regular.ttf`, `noto_sans_bengali_medium.ttf`, `noto_sans_bengali_bold.ttf` | Noto Sans Bengali 400, 500 and 700, unmodified (v33 static instances, 139 KB each; the 500 file is used for Bangla body in sunlight mode only, lead ruling 2026-10-07) | Google Fonts (`fonts.gstatic.com/s/notosansbengali/v33`), upstream https://github.com/notofonts/bengali | SIL Open Font License 1.1 |
| `noto_sans_latin_regular.ttf`, `noto_sans_latin_bold.ttf` | Noto Sans 400 and 700, subset with `pyftsubset` to Basic Latin, Latin-1 and general punctuation (29 KB each) | Google Fonts (`fonts.gstatic.com/s/notosans/v42`), upstream https://github.com/notofonts/latin-greek-cyrillic | SIL Open Font License 1.1 |

Copyright The Noto Project Authors (the exact notice is in the name table of each file). Licensed under the SIL Open Font License, Version 1.1
(https://openfontlicense.org). The OFL permits bundling the fonts in an application; the subset Latin files are
"Modified Versions" under the OFL and keep the original font name only as permitted for unreserved names (Noto has no
Reserved Font Name). The copyright notices and the full OFL text ship inside every APK as
`assets/licenses/OFL-noto-fonts.txt` (OFL section 2); the Settings licence screen (android-sr lane) can show that file.

Why these four (docs/24 s5.6, F-SYS-018): Bangla mode renders with Noto Sans Bengali, which also covers Basic Latin, so
usernames, codes and English words in Bangla screens use one consistent face; English mode renders with the subset Noto
Sans. Two weights each (Bangla adds Medium for sunlight body). Total 475 KB, well inside the 30 MB APK budget of docs/24 s5.7.
