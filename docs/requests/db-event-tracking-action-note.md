# Request (db → backend:analytics): drop the free-text note from `tracking_action.created`

`DailyTrackingApi` writes a `tracking_action.created` outbox event, catalogued as v1 in V0022 so it is accepted. Its
payload carries the supervisor's free-text `note`. The outbox rule (V0017, `docs/data-events.md`) is ids, codes and
amounts only: the outbox is `pii: none`, and Phase 2 consumers read it without PII grants. The note is already in
`app.audit_log.after`.

**Asked:**
1. Write the event without `note`. Removing a key is a breaking change, so tell the db lane and V0023+ adds
   `tracking_action.created` v2 (and deprecates v1).
2. Set `payload_version` explicitly when writing events.
3. Before shipping any new outbox event type, ask here for its catalogue row. An uncatalogued type is refused, and the
   producer gets a 500.
