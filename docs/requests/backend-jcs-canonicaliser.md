# Request (backend-core → shared): RFC 8785 canonicaliser in shared:contract

**What.** docs/24 s3.3 says `payload_sha256` is computed with "the RFC 8785 canonicaliser that `shared:contract` provides"
and s8.3 makes the phone sign records (`sig`) over the same canonical form. `shared:contract` has no canonicaliser yet.

**Interim (2026-10-07).** The server's copy is `backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/Jcs.kt`
(`Jcs.canonicalize(JsonElement): String`, `Jcs.sha256`), with RFC 8785 vectors in `backend/sync/.../JcsTest.kt`
(ECMAScript number form, UTF-16 member order, minimal string escapes). Marked `// REQUEST:` in the source.

**Ask.** The shared lane moves it to `shared:contract` (commonMain, so Android signs with the same code), keeps the
tests, and backend:sync switches to it. Same signature, so the switch is one import. Until then the phone lane must
not write its own: copy this one.

**Why it matters.** If the phone and the server canonicalise differently, every signed header record would be
quarantined `device_integrity_failed` once devices hold real keys.
