// Typed API client over the generated contract (openapi-fetch + openapi.d.ts). Server side only: the BFF owns tokens.
import createClient, { type Middleware } from "openapi-fetch";
import type { paths, Problem } from "@/contract/types";

export function apiBase(): string {
  return (process.env.ARON_API_BASE_URL ?? "http://127.0.0.1:4010").replace(/\/+$/, "");
}

/** A response without `X-Aron-Api: 1` came from the edge (WAF page, gateway timeout): a transport failure, never a business answer (docs/24 s3.1.6). */
export class TransportError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "TransportError";
  }
}

const aronMarker: Middleware = {
  onRequest({ request }) {
    request.headers.set("X-Request-Id", crypto.randomUUID());
    return request;
  },
  onResponse({ response }) {
    if (response.headers.get("x-aron-api") !== "1") {
      throw new TransportError("response without X-Aron-Api marker", response.status);
    }
    return response;
  },
};

export function apiClient(token?: string, extraHeaders?: Record<string, string>) {
  const client = createClient<paths>({
    baseUrl: apiBase(),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extraHeaders },
    cache: "no-store",
  });
  client.use(aronMarker);
  return client;
}

/** The result of a call, normalised: either data, or a problem (a synthetic one for transport failures). */
export type ApiOutcome<T> =
  | { ok: true; status: number; data: T; response: Response }
  | { ok: false; status: number; problem: Problem; response?: Response };

export function transportProblem(status = 503): Problem {
  return {
    type: "urn:aron:problem:err_service_unavailable",
    title: "Service unavailable",
    status: status >= 400 && status <= 599 ? status : 503,
    code: "ERR_SERVICE_UNAVAILABLE",
    request_id: "00000000-0000-4000-8000-000000000000",
    retryable: true,
  };
}

/** Normalise an openapi-fetch result (data | error) into ApiOutcome without losing the problem `code`. */
export async function outcome<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<ApiOutcome<T>> {
  try {
    const r = await call;
    if (r.response.ok && r.data !== undefined) return { ok: true, status: r.response.status, data: r.data, response: r.response };
    if (r.response.ok) return { ok: true, status: r.response.status, data: undefined as T, response: r.response };
    const p = r.error as Partial<Problem> | undefined;
    const problem: Problem = p && typeof p === "object" && typeof p.code === "string" ? (p as Problem) : { ...transportProblem(r.response.status), code: r.response.status === 401 ? "ERR_UNAUTHENTICATED" : r.response.status === 403 ? "ERR_FORBIDDEN" : "ERR_INTERNAL" };
    return { ok: false, status: r.response.status, problem, response: r.response };
  } catch (e) {
    if (e instanceof TransportError) return { ok: false, status: e.status ?? 503, problem: transportProblem(e.status) };
    return { ok: false, status: 503, problem: transportProblem(503) };
  }
}
