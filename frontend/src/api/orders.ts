import { request } from "./client";
import type { OrderCreated, OrderView, Profile, QuoteItem } from "./types";

/** Totals are deliberately not sent: the server recomputes the quote. */
export function createOrder(sessionId: string | null, profile: Profile, items: QuoteItem[]): Promise<OrderCreated> {
  return request<OrderCreated>("/api/orders", { method: "POST", body: { sessionId, profile, items } });
}

export function getOrder(orderId: string): Promise<OrderView> {
  return request<OrderView>(`/api/orders/${encodeURIComponent(orderId)}`);
}
