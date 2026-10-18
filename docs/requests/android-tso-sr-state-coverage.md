# SR state coverage: findings from the android-tso lane (2026-10-17)

Scope: SR Home, Sale entry, Review and Memo (menu and detail). 21 new Linux goldens in `SrStateScreenshotTests`
(Bangla, 360 x 800 dp, tier B): Home loading, no route + offline (light and Sunlight), expired bundle, day complete, 200 percent
font; Sale empty catalog (light and Sunlight), empty cart, long Bangla SKU name, 200 percent; Review empty cart and 200 percent;
Memo menu empty (light and Sunlight), list with due chip (light, Sunlight, 200 percent), detail with reprint limit, detail 200 percent.
Already held before: Home/Sale/Review/Memo detail in bn, en, Sunlight and 1.3 font. No SR logic or screen was edited.

State matrix (E = empty, L = loading, O = offline, X = error, B = long Bangla, S = Sunlight):
| Screen | E | L | O | X | B | S |
|---|---|---|---|---|---|---|
| Home | no-route (new) | skeleton (new golden) | no-route + offline chip (new) | expired bundle (new) | 200 percent (new) | no-route (new), full (old) |
| Sale entry | empty catalog, empty cart (new) | none: reads local data, no spinner needed | n/a (offline by design) | n/a | long name, 200 percent (new) | empty catalog (new), full (old) |
| Review | empty cart with "nothing sold" banner (new) | n/a | n/a | the banner is the error state | 200 percent (new) | old |
| Memo menu | empty (new) | n/a | n/a | n/a | list at 200 percent (new) | empty, list (new) |
| Memo detail | n/a | n/a | n/a | reprint limit + edit blocked (new) | 200 percent (new) | old |

Gaps for the lead (presentation only, SR owners decide), severity low unless noted:
1. **Sale entry, 200 percent: a SKU name wraps mid-word** ("ম্যাক্সআ" / "র ১০এস"), and the dock chip row cuts a chip. Kit-level; a name-column min width or `softWrap` hint would fix it. Low.
2. **Home, 200 percent: the title line truncates the username** ("SR - Testing Banani (sr3...") although the route and date lines have room. Low.
3. **Memo detail: the memo number mixes Latin "sr" with Bengali digits** (`sr৩৩৪০০১-২৬১০০৫-০০১`); the manual shows the number as issued. Decide whether memo numbers stay ASCII (they are an identifier the printed slip carries) . Medium: a rep reading it to the office must see the same number as the slip.
4. **Sale entry, empty cart state** shows no hint line, only the disabled bar; the empty catalog has a text. Low.
5. **Memo detail at 200 percent**: the bottom bar with two reason lines takes about a third of the screen, so only two item rows show above it. Acceptable, scrolls; noted for the font-scale device check.
No offline or error defect was found: Sale and Memo read local data only, so no spinner or offline banner is expected there.
