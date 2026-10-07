# Request: where the portal gets sub-channel ids (lane web-admin, 2026-10-07)

`Outlet.sub_channel_id`, `OutletWrite.sub_channel_id` and `OutletRequestVerifyRequest.sub_channel_id` are integer ids, but the
contract lists sub-channels only as code-list items of `GET /v1/admin/code-lists` (key `sub_channel`) whose items have a string
`code`, no id. The outlet form therefore shows a plain number box for the sub-channel.

**Ask.** Either add `id` to `CodeItem` (read-only) for the lists that outlets reference, or add `GET /v1/admin/sub-channels`
(id, code, label_en, label_bn), so the form can show a select with Bangla labels.

**Local stub.** `sub_channel_id` is `kind: "int"` in `web/src/app/admin/_entities/outlets.ts`. State: open.
