# Request (db → lead; FYI android-sr, android-amo, backend:sync): code-list choices in V0019

V0019 ships the system code lists in every environment (audit AUD-DA-04). Codes are immutable once shipped, so these
choices are permanent. Please copy them to DECISIONS.md, which lanes may not edit.

1. **`day_exception_reason`:** docs/16 s3.6 (D-39) and docs/19 `cfg.day.exception_reasons` disagree. V0019 follows
   docs/16, which owns the data model: `rain_flood`, `hartal`, `market_closed`, `dh_out_of_stock`, `breakdown` and `sick`,
   plus docs/19's `other`. docs/19 says `dh_no_stock` and `sick_no_leave`. Please align docs/19 with V0019.
2. **`channel` and `geo_class`:** the code pattern is lower case, so the codes are `gt`, `dcc`, `astha`, `rcc`, `mt`,
   `horeca` and `hill`, `urban`, `semi_urban`, `rural`. The canonical value (the CHECK and the contract enum) is in
   `attrs.value`, and geo_class also carries `attrs.ordinal`, its config `scope_id`. `sub_channel` items label the nine
   D-258 rows of `app.sub_channel` (`attrs.value` = its code, `attrs.channel`). Those rows now ship in V0019 as well.
3. **New `attrs` keys, not yet in the contract's CodeItem description:**
   - `system: true` marks a code the server assigns, such as `force_reason.no_outlet_location`. Apps never offer it in a picker.
   - `roles: [...]` limits a code to those roles, such as `force_reason.manual_override` for the AMO.
   - The SR force-sale picker should therefore show 2 reasons, as observed. Please let the contract owner add these keys to CodeItem.attrs.
4. **Lists the specs leave empty** each got one minimal code, so required reasons are not blocked:
   - `stock_variance_reason.other`
   - `outlet_close_reason.permanently_closed` (the observed confirm text)
   - `submit_void_reason.accidental_submit` and `.other` (D-539)
   
   `void_reason` takes docs/19's four proposed codes (Q43).
5. **Bangla labels** are shipped only where the docs or the manuals give them. The others are NULL, and the apps fall back to `label_en`.
   The sponsor go-live checklist item in docs/status/db.md asks AKTCL to confirm items 4–5 and supply the labels.
