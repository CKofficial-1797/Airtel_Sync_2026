import { describe, expect, it } from "vitest";
import { kiranaQuote, line } from "../test/fixtures";
import { applyQuoteResponse, itemsFromQuote, withAddOn, withQuantity, withoutItem } from "./useQuote";

const base = itemsFromQuote(kiranaQuote());

describe("quote item payloads", () => {
  it("toggling an add-on ON adds it with its default quantity", () => {
    expect(withAddOn(base, { productId: "P23", defaultQuantity: 2 }, true)).toEqual([
      { productId: "P01", quantity: 1 },
      { productId: "P07", quantity: 2 },
      { productId: "P23", quantity: 2 },
    ]);
  });

  it("toggling an add-on OFF removes it", () => {
    const withCctv = withAddOn(base, { productId: "P23", defaultQuantity: 2 }, true);
    expect(withAddOn(withCctv, { productId: "P23", defaultQuantity: 2 }, false)).toEqual(base);
  });

  it("an upgrade swaps out the product it replaces, and back again", () => {
    const pro = [{ productId: "P02", quantity: 1 }, { productId: "P16", quantity: 10 }];
    const upgraded = withAddOn(pro, { productId: "P03", defaultQuantity: 1 }, true, "P02");
    expect(upgraded).toEqual([{ productId: "P16", quantity: 10 }, { productId: "P03", quantity: 1 }]);
    expect(withAddOn(upgraded, { productId: "P03", defaultQuantity: 1 }, false, "P02")).toEqual([
      { productId: "P16", quantity: 10 },
      { productId: "P02", quantity: 1 },
    ]);
  });

  it("quantities are clamped to 1..500", () => {
    expect(withQuantity(base, "P07", 3)).toContainEqual({ productId: "P07", quantity: 3 });
    expect(withQuantity(base, "P07", 0)).toContainEqual({ productId: "P07", quantity: 1 });
    expect(withQuantity(base, "P07", 900)).toContainEqual({ productId: "P07", quantity: 500 });
  });

  it("removing a line drops it from the items", () => {
    expect(withoutItem(base, "P07")).toEqual([{ productId: "P01", quantity: 1 }]);
  });
});

describe("applyQuoteResponse", () => {
  it("a removed[] answer updates the lines and produces a notice", () => {
    const response = kiranaQuote({
      lines: [line("P07", "Business Postpaid 299", 2, 299)],
      monthlyTotalInr: 598,
      removed: [
        {
          productId: "P11",
          productName: "Static IP",
          quantity: 1,
          reasons: [{ code: "DEPENDENCY_REMOVED", message: "Static IP removed because Biz Fibre 100 was removed" }],
        },
      ],
    });
    const { items, notices } = applyQuoteResponse(response);
    expect(items).toEqual([{ productId: "P07", quantity: 2 }]);
    expect(notices).toHaveLength(1);
    expect(notices[0]?.reasons[0]?.message).toBe("Static IP removed because Biz Fibre 100 was removed");
  });
});
