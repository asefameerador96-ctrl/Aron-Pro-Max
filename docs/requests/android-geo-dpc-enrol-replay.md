# Request (android-geo-dpc → backend, N-031): a repeated enrolment with the same device and key is a replay, not a second use

**Row:** N-030 ("a reused token is refused").

A phone that sent `POST /v1/devices/enrol` and lost the response (weak signal at the warehouse) retries with the **same** `enrolment_token`, `device_uuid` and `public_key` (the phone keeps them across kills; `EnrolmentCoordinator`). With `max_uses = 1` the server would answer `ERR_ENROLMENT_TOKEN_EXHAUSTED`, and the phone, already registered on the server, could never finish enrolment without a new QR and a factory reset.

Needed in the enrolment service:
- If the token's earlier use enrolled the same `device_uuid` with the same public key (and the same package), answer **201 with the stored `EnrolDeviceResponse`** (current policy), not a refusal and not a second use.
- A different `device_uuid` or key on an exhausted token stays `ERR_ENROLMENT_TOKEN_EXHAUSTED` (this is the "reused token is refused" acceptance).
- A test for both cases.

The phone side treats any 4xx with an `ERR_ENROLMENT_*` code as final and deletes its pending key; 5xx, 429 and transport failures are retried with the identical request.
