# backend-core: U+0000 in a request body is a 500

A JSON string containing `\u0000` reaches PostgreSQL, which rejects it (SQLSTATE 22021) and the API answers 500 `ERR_INTERNAL` (retryable, so phones retry forever).
backend-admin guards leave and feedback locally (`AdminSupport.noNul`). The platform fix is one place: in `receiveStrict` (platform `Requests.kt`) refuse a decoded
string containing U+0000 with 400 `ERR_VALIDATION` (`errors[].code = invalid_character`), or map SQLSTATE 22021, 22003 and 22P05 to 400 in the problem mapper.
