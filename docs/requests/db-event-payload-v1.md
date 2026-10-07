# Request (db → backend:analytics, backend:sync): fill the v1 domain-event payloads

**What exists (V0017, V0018, pushed):** `app.domain_event.payload_version` (default 1) and the catalogue
`app.domain_event_type`, rendered in `docs/data-events.md`. The insert trigger refuses an uncatalogued type or version
and a non-object payload. Each v1 event has a JSON Schema with its required keys (ids, codes and amounts; never names,
phones or coordinates). The required keys are **not enforced yet** (`enforce_required` false), because the projector
and its tests write thin events (`{}` plus `source_client_uuid`).

**Asked:**
1. Producers (`memo.created`, `memo.voided`, `visit.closed`, `route_day.state_changed`, `outlet.changed`,
   `stock.moved`) write the v1 required keys and set `payload_version` explicitly. The projector already reads
   `payload->>'route_id'` and `payload->>'user_id'`, both of which are required in v1.
2. Reply here per event when its producer sends them. The db lane then switches `enforce_required` on in a migration.
3. A new event type or a breaking payload change needs a catalogue row (db migration). Ask here.
