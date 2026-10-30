import type { ApiErrorBody, Quote } from "./types";

/** Any non-2xx answer from Spring. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly fieldErrors: string[];

  constructor(status: number, message: string, code?: string, fieldErrors: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

/** 503 AI_UNAVAILABLE: the advisor is down; the user's message is already saved. */
export class AiUnavailableError extends ApiError {
  constructor(message: string) {
    super(503, message, "AI_UNAVAILABLE");
    this.name = "AiUnavailableError";
  }
}

/** 409 QUOTE_CHANGED: the server recomputed the quote and dropped items; show the new quote. */
export class QuoteChangedError extends ApiError {
  readonly quote: Quote;

  constructor(message: string, quote: Quote) {
    super(409, message, "QUOTE_CHANGED");
    this.name = "QuoteChangedError";
    this.quote = quote;
  }
}

/** Spring not reachable (network down, dev proxy cannot connect). */
export class NetworkError extends ApiError {
  constructor() {
    super(0, "Network error", "NETWORK");
    this.name = "NetworkError";
  }
}

interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  signal?: AbortSignal;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method: options.method ?? "GET",
      headers: options.body === undefined ? undefined : { "Content-Type": "application/json" },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw e;
    }
    throw new NetworkError();
  }

  if (res.ok) {
    return (await res.json()) as T;
  }

  let body: Partial<ApiErrorBody> | null = null;
  try {
    body = (await res.json()) as Partial<ApiErrorBody>;
  } catch {
    body = null;
  }
  const message = body?.message ?? res.statusText ?? "Request failed";
  if (res.status === 503 && body?.code === "AI_UNAVAILABLE") {
    throw new AiUnavailableError(message);
  }
  if (res.status === 409 && body?.code === "QUOTE_CHANGED" && body.quote) {
    throw new QuoteChangedError(message, body.quote);
  }
  if (res.status === 502 || res.status === 504) {
    throw new NetworkError();
  }
  throw new ApiError(res.status, message, body?.code, body?.errors ?? []);
}

export function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError";
}
