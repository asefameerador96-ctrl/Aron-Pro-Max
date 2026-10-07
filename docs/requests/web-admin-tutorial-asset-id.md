# Request: asset_id on TutorialAdmin (or optional on PATCH)

Lane: web-admin (F-ADM-026 tutorials).

`PATCH /v1/admin/tutorials/{id}` takes `TutorialWrite`, where `asset_id` is required, but `TutorialAdmin` (list) does not return `asset_id`. A title, order, role or status edit therefore cannot keep the current file.

Proposed: return `asset_id` in `TutorialAdmin`, or make `asset_id` optional on update (omitted keeps the file).

Stub: the edit form requires a new file when the row has no `asset_id`.
