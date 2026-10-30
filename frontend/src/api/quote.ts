import { request } from "./client";
import type { Product, Profile, Quote, QuoteItem } from "./types";

export function fetchQuote(
  profile: Profile,
  items: QuoteItem[],
  removedProductIds: string[],
  signal?: AbortSignal,
): Promise<Quote> {
  return request<Quote>("/api/quote", { method: "POST", body: { profile, items, removedProductIds }, signal });
}

let productsCache: Promise<Product[]> | null = null;

/** Catalogue (for one-line descriptions on the quote page). Prices are never taken from here. */
export function getProducts(): Promise<Product[]> {
  productsCache ??= request<Product[]>("/api/products").catch((e: unknown) => {
    productsCache = null;
    throw e;
  });
  return productsCache;
}
