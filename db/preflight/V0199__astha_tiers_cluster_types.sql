-- Pre-flight of V0199 (read-only): the mirror of its two raising DATA guards, run by the migrate role BEFORE Flyway applies anything
-- (backend:platform MigrationPreflight; docs/30 "Pre-flight of data-dependent migrations"). V0199 adds the Astha tier Titanium and raises when
--   (1) app.sub_channel already has the code 'Titanium' under a channel other than Astha, or
--   (2) the sub_channel code-list item 'titanium' already exists with attrs->>'channel' other than Astha (an item without a channel counts as other).
-- Output: where (the channel CODE, one of the six, or 'other'/'none' for a code-list item whose attribute is none of them) and the row ids: NEVER a name.
-- Both rows are checked exactly as V0199 checks them (same codes, same comparison). Their repair is a business decision, so there is no automatic repair.
SELECT 'sub_channel under channel ' || s.channel AS where_code, array_agg(s.id ORDER BY s.id) AS ids
  FROM app.sub_channel s
 WHERE s.code = 'Titanium' AND s.channel <> 'Astha'
 GROUP BY s.channel
UNION ALL
SELECT 'code-list item under channel ' ||
       CASE WHEN i.attrs->>'channel' IS NULL THEN 'none'
            WHEN i.attrs->>'channel' IN ('GT', 'DCC', 'Astha', 'RCC', 'MT', 'HoReCa') THEN i.attrs->>'channel'
            ELSE 'other' END AS where_code,
       array_agg(i.id ORDER BY i.id) AS ids
  FROM app.code_list_item i
 WHERE i.list_key = 'sub_channel' AND i.code = 'titanium' AND i.attrs->>'channel' IS DISTINCT FROM 'Astha'
 GROUP BY 1
 ORDER BY 1
