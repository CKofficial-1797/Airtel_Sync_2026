import { request } from "./client";
import type { DemoAdvance, InstallSlotName, InstallSlots, TrackerView } from "./types";

function orderPath(orderId: string, suffix: string): string {
  return `/api/orders/${encodeURIComponent(orderId)}${suffix}`;
}

/** auto: the page's background refresh; the server only counts an owner opening or refreshing the page. */
export function getTracker(orderId: string, auto = false): Promise<TrackerView> {
  return request<TrackerView>(orderPath(orderId, auto ? "/tracker?auto=1" : "/tracker"));
}

export function getInstallSlots(orderId: string): Promise<InstallSlots> {
  return request<InstallSlots>(orderPath(orderId, "/install-slots"));
}

export function bookInstallSlot(orderId: string, date: string, slot: InstallSlotName): Promise<TrackerView> {
  return request<TrackerView>(orderPath(orderId, "/install-slot"), { method: "POST", body: { date, slot } });
}

export function lookupOrder(orderRef: string, pincode: string): Promise<{ orderId: string }> {
  return request<{ orderId: string }>("/api/track/lookup", { method: "POST", body: { orderRef, pincode } });
}

export function getDemoStatus(): Promise<{ enabled: boolean }> {
  return request<{ enabled: boolean }>("/api/demo/status");
}

export function demoAdvance(orderId: string, body: DemoAdvance = {}): Promise<TrackerView> {
  return request<TrackerView>(`/api/demo/orders/${encodeURIComponent(orderId)}/advance`, { method: "POST", body });
}
