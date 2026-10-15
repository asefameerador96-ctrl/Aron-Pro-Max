// Server-side feature flags of the admin portal. Each is an environment variable read per request, default OFF, so a screen can ship
// dark and be switched on in the environment (not in code) once the data behind it is ready.

/** Paper data entry (POST /v1/admin/data-entry): dark until the reports' dw `entry_source` is on INT (docs/requests/db-backend-reports-entry-source.md). */
export function paperEntryEnabled(env: Record<string, string | undefined> = process.env): boolean {
  return env.ARON_FEATURE_PAPER_ENTRY === "1";
}
