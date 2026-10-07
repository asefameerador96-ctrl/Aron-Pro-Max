# android-sys: `media_meta` record in the outbox (for android-core)

F-SYS-010 sends one `media_meta` record per photo after its blob is stored (contract `MediaMetaRecord` /
`MediaMetaPayload`: "The record's client_uuid IS the media UUID; sent after the blob upload succeeded"). The record must go
through the outbox like every other record, and android-sys does not edit core-database. Please add:

```kotlin
// CaptureRepository: own family, rank 0, one transaction; a duplicate media uuid is a no-op (return false), not an error,
// because the media worker may repeat it after a kill.
suspend fun recordMediaMeta(meta: MediaMetaCapture): Boolean

data class MediaMetaCapture(
    val mediaUuid: String,            // = record client_uuid
    val meta: CaptureMeta,            // envelope (business_date = the photo's business date)
    val purpose: String,              // MediaPurpose
    val refType: String,              // RecordType of the owning record
    val refClientUuid: String,
    val sha256: String, val phash: String?,
    val bytes: Int, val width: Int, val height: Int,   // mime is always image/jpeg
    val blobPath: String,             // from the SAS response
    val takenAt: String,              // ISO-8601 UTC
    val fixClientUuid: String?,       // payload.fix = the stored geo_fix row with this uuid (null -> fix: null)
)
```

plus the `RecordMapping` for `media_meta` (no new domain table needed: the media queue lives in `files/media/u<id>/`).

Also, read-only, for the media worker: I read `db.outboxDao().byClientUuid(refUuid)?.state` to wait for the owning record's
ack before uploading (docs/24 s4.11 step 1). Tell me if you would rather expose that as a small `RecordAckProbe` in
core-database.

Until this lands, android-sys uses a `MediaMetaSink` port; photos upload and stay in state `uploaded` (retrying the sink on
every run), so nothing is lost and nothing is sent twice.
