import { useCallback, useEffect, useRef, useState } from "react";
import { isAbort, NetworkError } from "../api/client";
import { fetchQuote } from "../api/quote";
import type { AddOn, Profile, Quote, QuoteItem, RemovedItem } from "../api/types";

export const MIN_QTY = 1;
export const MAX_QTY = 500;
const DEBOUNCE_MS = 300;

// ---------------------------------------------------------------- pure helpers (unit-tested)
export function itemsFromQuote(quote: Quote): QuoteItem[] {
  return quote.lines.map((l) => ({ productId: l.productId, quantity: l.quantity }));
}

export function withQuantity(items: QuoteItem[], productId: string, quantity: number): QuoteItem[] {
  const q = Math.min(MAX_QTY, Math.max(MIN_QTY, Math.round(quantity)));
  return items.map((i) => (i.productId === productId ? { ...i, quantity: q } : i));
}

export function withoutItem(items: QuoteItem[], productId: string): QuoteItem[] {
  return items.filter((i) => i.productId !== productId);
}

/**
 * Toggling an add-on. ON adds it with its default quantity (an upgrade also takes out the product it replaces);
 * OFF removes it (an upgrade puts the replaced product back).
 */
export function withAddOn(
  items: QuoteItem[],
  addOn: Pick<AddOn, "productId" | "defaultQuantity">,
  on: boolean,
  replaces?: string | null,
): QuoteItem[] {
  if (on) {
    const kept = items.filter((i) => i.productId !== addOn.productId && i.productId !== replaces);
    const replacedQty = items.find((i) => i.productId === replaces)?.quantity;
    return [...kept, { productId: addOn.productId, quantity: replacedQty ?? addOn.defaultQuantity }];
  }
  const current = items.find((i) => i.productId === addOn.productId);
  const rest = withoutItem(items, addOn.productId);
  return replaces && current && !rest.some((i) => i.productId === replaces)
    ? [...rest, { productId: replaces, quantity: current.quantity }]
    : rest;
}

/** After the engine answers: the new items are exactly its kept lines; dropped items become notices. */
export function applyQuoteResponse(quote: Quote): { items: QuoteItem[]; notices: RemovedItem[] } {
  return { items: itemsFromQuote(quote), notices: quote.removed };
}

// ---------------------------------------------------------------- hook
export function useQuote(profile: Profile | null, initialQuote: Quote | null) {
  const [quote, setQuote] = useState<Quote | null>(initialQuote);
  const [items, setItems] = useState<QuoteItem[]>(initialQuote ? itemsFromQuote(initialQuote) : []);
  const [notices, setNotices] = useState<RemovedItem[]>([]);
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<"NETWORK" | "GENERIC" | null>(null);

  const itemsRef = useRef(items);
  const removedRef = useRef<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inFlight = useRef<AbortController | null>(null);

  const replaceQuote = useCallback((q: Quote) => {
    const next = applyQuoteResponse(q);
    itemsRef.current = next.items;
    setItems(next.items);
    setQuote(q);
    if (next.notices.length) setNotices(next.notices);
  }, []);

  useEffect(() => {
    if (initialQuote) {
      itemsRef.current = itemsFromQuote(initialQuote);
      setItems(itemsRef.current);
      setQuote(initialQuote);
    }
  }, [initialQuote]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
      inFlight.current?.abort();
    },
    [],
  );

  const reprice = useCallback(
    (next: QuoteItem[], removedIds: string[]) => {
      if (!profile) return;
      itemsRef.current = next;
      setItems(next);
      removedRef.current = [...new Set([...removedRef.current, ...removedIds])];
      setUpdating(true);
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(async () => {
        inFlight.current?.abort(); // a newer edit wins; stale answers are dropped
        const controller = new AbortController();
        inFlight.current = controller;
        const removed = removedRef.current;
        removedRef.current = [];
        try {
          replaceQuote(await fetchQuote(profile, itemsRef.current, removed, controller.signal));
          setError(null);
        } catch (e) {
          if (isAbort(e)) return;
          setError(e instanceof NetworkError ? "NETWORK" : "GENERIC");
        } finally {
          if (inFlight.current === controller) {
            inFlight.current = null;
            setUpdating(false);
          }
        }
      }, DEBOUNCE_MS);
    },
    [profile, replaceQuote],
  );

  const setQuantity = useCallback(
    (productId: string, quantity: number) => reprice(withQuantity(itemsRef.current, productId, quantity), []),
    [reprice],
  );

  const removeItem = useCallback(
    (productId: string) => {
      if (itemsRef.current.length <= 1) return;
      reprice(withoutItem(itemsRef.current, productId), [productId]);
    },
    [reprice],
  );

  const toggleAddOn = useCallback(
    (addOn: Pick<AddOn, "productId" | "defaultQuantity">, on: boolean, replaces?: string | null) => {
      const next = withAddOn(itemsRef.current, addOn, on, replaces);
      if (next.length === 0) return;
      reprice(next, on ? [] : [addOn.productId]);
    },
    [reprice],
  );

  const dismissNotice = useCallback(
    (productId: string) => setNotices((n) => n.filter((r) => r.productId !== productId)),
    [],
  );

  return { quote, items, notices, updating, error, setQuantity, removeItem, toggleAddOn, dismissNotice, replaceQuote };
}
