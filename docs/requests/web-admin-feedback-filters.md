# Request: category and status filters on GET /v1/feedback

Lane: web-admin (F-ADM-028 feedback inbox).

## Need
The acceptance text says the inbox lists feedback "by category and status". `listFeedback` takes only `from`, `to`, `limit` and `cursor`.

## Proposed shape
Add optional query parameters `category_code` (string, pattern of FeedbackPayload.category_code) and `status` (FeedbackStatus). Order newest first.

## Stub
The inbox shows category and status as columns, with no filters. The status can be set (PATCH /v1/feedback/{feedback_uuid}, with a reason).
