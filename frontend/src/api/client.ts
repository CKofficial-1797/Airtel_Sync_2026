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

/** 403 CONSENT_REQUIRED: KYC needs an active consent; send the user back to the consent step. */
export class ConsentRequiredError extends ApiError {
  constructor(message: string) {
    super(403, message, "CONSENT_REQUIRED");
    this.name = "ConsentRequiredError";
  }
}

/** 503 EXTRACTION_UNAVAILABLE: the document could not be read right now; nothing was saved, retry. */
export class ExtractionUnavailableError extends ApiError {
  constructor(message: string) {
    super(503, message, "EXTRACTION_UNAVAILABLE");
    this.name = "ExtractionUnavailableError";
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
  headers?: Record<string, string>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  // FormData sets its own multipart Content-Type (with the boundary).
  const form = options.body instanceof FormData ? options.body : undefined;
  const contentType: Record<string, string> = options.body === undefined || form ? {} : { "Content-Type": "application/json" };
  const headers = { ...contentType, ...options.headers };
  let res: Response;
  try {
    res = await fetch(path, {
      method: options.method ?? "GET",
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: options.body === undefined ? undefined : (form ?? JSON.stringify(options.body)),
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
  if (res.status === 403 && body?.code === "CONSENT_REQUIRED") {
    throw new ConsentRequiredError(message);
  }
  if (res.status === 503 && body?.code === "EXTRACTION_UNAVAILABLE") {
    throw new ExtractionUnavailableError(message);
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
