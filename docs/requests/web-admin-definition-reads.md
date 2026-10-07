# Request: survey, rubric and content reads that round-trip into the write schemas (F-ADM-020)

Lane: web-admin.

The read and write schemas of the definition pages do not match, so an edit cannot start from what the API returns:
1. `SurveyAdmin.questions[]` has `question_id` (number) and `option_codes`; `SurveyWrite.questions[]` needs `key` (string) and has no `option_codes`, and adds `required`, `show_if_key`, `show_if_bool`, `photo` (read has `requires_photo`). The question `key` is not readable.
2. `RubricAdmin.criteria[].answer_type` is `score_1_5 | text | bool`; `RubricWrite.criteria[].answer_type` is `stars_1_5 | bool | text`; the criterion `key` is not readable (read has `criterion_id`).
3. `ContentAdmin` returns `asset_url` but not `asset_id`; `ContentWrite.asset_id` is required (same gap as docs/requests/web-admin-tutorial-asset-id.md).
4. The row version for If-Match (`updateSurvey`, `updateRubric`, `updateContent`) is only on the read as `version` for surveys and rubrics; `ContentAdmin` has `version` through ContentItem. OK.

Proposed: return the write members on the admin read (`key`, `show_if_*`, `required`, `photo`, `option_codes` as writable, `asset_id`), and use one answer_type enum.

Stub: the pages create a definition (full write body) and publish a new version by re-entering the questions; an existing definition is shown read-only with a "Use as a template" action that copies the labels and generates keys from the order (`q1`, `q2`, ...), which the admin can edit. Marked `// REQUEST: web-admin-definition-reads`.
