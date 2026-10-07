# Request: web-config -> backend-admin: names for the Web Entry classes

Row F-WEB-050. The class split is built: `cfg.web.entry_classes` (list of sub-channel ids) is resolved for the zone; one class takes the
whole sale, several classes show one column each and must add up to the sale (issue minus return); the payload carries
`class_qty_base` (sub-channel id to quantity).

Gap: the config value holds ids only and the `sub_channel` code list has codes, not ids, so the columns read "Class 7". Needed: names
with the ids, for example `GET /v1/admin/config/resolve` returning `labels`, or an id on `CodeItem` of the `sub_channel` list.
`// REQUEST: web-config-entry-class-labels`
