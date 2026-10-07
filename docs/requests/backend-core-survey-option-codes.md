# backend-core to backend-admin: `option_codes` on survey questions (F-SR-021, SurveyWrite)

**Filed 2026-10-07 by backend-core (session 7).**

`SurveyWrite.yaml` defines `option_codes` per question, but `AdminContentApi.kt` `SurveyQuestionIn` / `StoredQuestion`
have no such member, and the strict body parser refuses unknown keys: an admin can save an `option` question and cannot
send its choices. The bundle (`sync/BundleContent.kt`, BC-67) already passes `option_codes` through from the stored
question JSON when present. **Ask:** accept, validate (`^[a-z][a-z0-9_]{1,40}$`, at most 20, required non-empty for
`answer_type = option`) and store `option_codes` with the question.
