# Contract 1.9 shapes for queue rows 4 and 5 (backend-core session 18, 2026-10-24)

For the lead's 1.9 batch (`docs/requests/contract-v1.9-queue.md` rows 4 and 5; D-NIGHT-DAYCLOSE-01, D-NIGHT-CHECKOUT-01).
Both are additive (new response members, one new extensible-enum value): no break for an old reader, no oasdiff override.
The server side is built on `lane/backend-core` behind the current DTOs (see "Server side ready" below); when the batch
lands only the generated DTOs and the regeneration step change.

## Row 4: Sales Submit digest (per device and per login, plus the rejected list)

### Which operation

The Sales Submit screen's Server column already reads **`GET /v1/sync/totals?business_date=`** (`getSyncTotals`, docs/24
s4.12: "`GET /v1/sync/totals?business_date=` gives the same for the Sales Submit screen"). There is no `GET /v1/sync/digest`;
`POST /v1/sync/digest` (`postSyncDigest`) is the 16-bucket re-send compare and stays unchanged. So row 4 adds one member,
`digest`, to `SyncTotalsResponse` (the GET answer only). It does **not** go on `ServerTotals`, which every batch response
repeats per date (`server_totals[]`): the rejected list would ride every upload.

### openapi.yaml (components/schemas)

```yaml
    SyncTotalsResponse:
      type: object
      required: [totals, day_states, digest]          # digest added (1.9)
      additionalProperties: false
      properties:
        totals: { $ref: '#/components/schemas/ServerTotals' }
        day_states:
          type: array
          items: { $ref: '#/components/schemas/RouteDayState' }
        supervisor_day:
          oneOf:
            - { $ref: '#/components/schemas/SupervisorDayState' }
            - type: 'null'
        digest: { $ref: '#/components/schemas/SalesSubmitDigest' }
    SalesSubmitDigest:
      type: object
      description: >-
        D-NIGHT-DAYCLOSE-01. The Sales Submit compare for the caller (user from the token) and `business_date`. `device` counts
        cover only the rows this phone uploaded (device from the token's device proof, never from the request); `login`
        counts cover every device and install of this login on the date. The day is in sync when, per type, the phone's
        committed rows are all acknowledged: phone committed = device.acknowledged + the device rows in `rejected`; a
        `login` count above the phone's count is information (other devices or earlier installs), never a mismatch.
      required: [business_date, device_uuid, by_type, rejected, rejected_truncated]
      additionalProperties: false
      properties:
        business_date: { $ref: '#/components/schemas/BusinessDate' }
        device_uuid:
          description: The device the `device` counts are for; null for a caller without a phone token (web), then every `device` is null.
          oneOf:
            - { $ref: '#/components/schemas/Uuid' }
            - type: 'null'
        by_type:
          type: object
          description: Every record type with at least one registry row of this login on the date (absent type = all zero).
          propertyNames: { $ref: '#/components/schemas/RecordType' }
          additionalProperties: { $ref: '#/components/schemas/DigestTypeCounts' }
        rejected:
          type: array
          maxItems: 200
          description: >-
            Rows of this login and date the server did not store (rejected, parked or held in quarantine) and has not stored
            since, newest first, from every device of the login (`from_this_device` says which). The phone shows each with
            the `ReasonTexts` text of `code`, also for a row it no longer holds (an earlier install).
          items: { $ref: '#/components/schemas/RejectedRecordView' }
        rejected_truncated: { type: boolean, description: True when more than 200 rows qualify (the oldest are left out). }
    DigestTypeCounts:
      type: object
      required: [device, login]
      additionalProperties: false
      properties:
        device:
          description: Null without a phone token.
          oneOf:
            - { $ref: '#/components/schemas/DigestDeviceCounts' }
            - type: 'null'
        login: { $ref: '#/components/schemas/DigestLoginCounts' }
    DigestDeviceCounts:
      type: object
      required: [received, acknowledged]
      additionalProperties: false
      properties:
        received: { type: integer, minimum: 0, description: 'Distinct client_uuids of this device, login and date the server holds an outcome for (stored, rejected, parked or quarantined).' }
        acknowledged: { type: integer, minimum: 0, description: 'Of those, stored (accepted now or earlier, also voided since; duplicates counted once).' }
    DigestLoginCounts:
      type: object
      required: [acknowledged]
      additionalProperties: false
      properties:
        acknowledged: { type: integer, minimum: 0, description: 'Stored rows of this login and date from every device and install (= ServerTotals.by_type.accepted).' }
    RejectedRecordView:
      type: object
      required: [client_uuid, type, business_date, status, code, final, from_this_device, last_received_at]
      additionalProperties: false
      properties:
        client_uuid: { $ref: '#/components/schemas/Uuid' }
        type: { $ref: '#/components/schemas/RecordType' }
        business_date: { $ref: '#/components/schemas/BusinessDate' }
        status:
          type: string
          enum: [rejected, parked, quarantined]
          description: rejected = refused for good; parked = refused for now, a re-send can store it; quarantined = held for review (the decision arrives in `resolutions`).
        code: { $ref: '#/components/schemas/RecordOutcomeCode' }
        final:
          type: boolean
          description: >-
            True when no re-send can change the outcome (status rejected, a parked row past its TTL, a discarded
            quarantine). A final row never blocks Sales Submit; a non-final `parked` row does until re-synced; an open
            quarantine does not (it is acknowledged, awaiting review).
        from_this_device: { type: boolean, description: False for a row another device or an earlier install of this login uploaded (always false without a phone token). }
        memo_no:
          description: The memo number of a memo row, or of the memo a memo_line, memo_discount, memo_void or due_collection names, when the server can read it.
          oneOf:
            - { $ref: '#/components/schemas/MemoNo' }
            - type: 'null'
        outlet_id: { type: [integer, 'null'], format: int64, minimum: 1, description: The outlet the row names (payload or envelope), when present. }
        last_received_at: { $ref: '#/components/schemas/Timestamp' }
```

Mapping to the lead's wording: "device: {committed, acknowledged}" becomes `device: {received, acknowledged}` (the server cannot
know what the phone committed; it knows what it received; the phone's committed count stays on the phone), and
"login_today" is `login` (the date is the request's `business_date`, which can be a past day).

### Rules the server applies (built, tests in backend/app `SalesSubmitDigestTest`)
- Counts come from `app.ingest_registry` (user from the token, `business_date`; device = the token's `device_id`).
  Stored = status `accepted` or `voided`; received = any status.
- `rejected` = rows of `app.sync_rejected` (`stored_at IS NULL`) and `app.sync_quarantine` (status `open` or `discarded`) of
  the user and date whose `client_uuid` the registry does not hold as stored; one entry per `client_uuid` (the newest).
  `final`: sync_rejected `NOT retryable OR finalised_at IS NOT NULL`; quarantine `discarded`. A row without a business date
  in the store (refused before the date could be read) is not listed (its count shows in the batch acks only).
- Never another user's rows (the token's user only; a TSO or AMO reads its own day only; no `user_id` parameter).

### docs/24 text (s4.12, after "gives the same for the Sales Submit screen")
"…and, in `digest`, the same counts for the calling phone alone (`device`) and for the login (`login`), plus the login's
rows the server did not store (`rejected[]`: type, code, final, memo number or outlet). In sync means every committed phone row
is acknowledged; a server count above the phone's is information (D-NIGHT-DAYCLOSE-01)."

## Row 5: `RiskSignalCode` `CHECKOUT_WITHOUT_SUBMIT`

### openapi.yaml
`RiskSignalCode.x-extensible-enum` gains `CHECKOUT_WITHOUT_SUBMIT` at the end (after `MEMO_DUPLICATE_SUSPECT`); no version
bump needed by itself (contract 1.3.1 rule), it rides 1.9. Mirrors: `ContractCatalogues.RiskSignalCode` (generator), web
`openapi.d.ts`, `RiskSignalApi.CODES` follows the catalogue.

### docs/24 s11.4 row (drift test reads it)
`| \`CHECKOUT_WITHOUT_SUBMIT\` | an SR's or AMO's check-out (\`attendance_event\` kind \`check_out\`) is stored and the user has no effective \`day_submit\` (not voided, any scope) for that business date at the end of the batch that stored it (D-NIGHT-CHECKOUT-01; subject the user; one per user-day) | — | 2 | 20 |`

### db (needs a db migration; db lane, one row)
`app.risk_signal_code_check` gains `CHECKOUT_WITHOUT_SUBMIT` (drop/add `NOT VALID` + validate, as V0077/V0078);
`cfg.geo.integrity_weight` default gains `{"CHECKOUT_WITHOUT_SUBMIT": 20}`. Until both exist the server raises nothing.

### Server rule (built behind a switch, tests in backend/app `CheckoutWithoutSubmitTest`)
- Evaluated once per stored check-out (never on a duplicate resend), after the whole batch is stored, so a Sales Submit and
  a check-out in one batch never raise it, in either order.
- Roles SR and AMO (TSO check-outs are not gated by D-NIGHT-CHECKOUT-01).
- `subject_type = 'user'`, `subject_id = user id`, `ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING`:
  idempotent, one signal per user-day; evidence `{checkout_client_uuid, checked_out_at, day_submit_found: false}`.
- The check-out itself is stored unchanged (the phone gate is the rule); the insert runs in a savepoint, so a missing
  constraint value can never fail the batch.
- Switch: on when `ContractCatalogues.RiskSignalCode` names the code AND the database's `risk_signal_code_check` accepts it
  (checked once per process). Both arrive with the 1.9 batch and the db row; nothing to flip by hand.
- Known gap (stated, not fixed): a Sales Submit uploaded in a later batch than the check-out (phone offline with a
  parked day_submit) leaves the signal open; a reviewer dismisses it. The phone gate makes this rare.
