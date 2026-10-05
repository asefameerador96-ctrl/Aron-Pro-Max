export interface FieldError {
  pointer: string;
  code: string;
  message?: string;
}

/** What the browser sends to the BFF for a write. The reason is mandatory on every write. */
export interface WriteRequest {
  values: Record<string, unknown>;
  reason: string;
  /** Row version loaded with the form (If-Match). Update only. */
  version?: number;
}
