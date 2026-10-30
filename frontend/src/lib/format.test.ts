import { describe, expect, it } from "vitest";
import { formatDate, formatInr } from "./format";
import { maskGstin } from "./mask";

describe("formatInr", () => {
  it("uses Indian digit grouping and whole rupees", () => {
    expect(formatInr(123456)).toBe("₹1,23,456");
    expect(formatInr(1597)).toBe("₹1,597");
    expect(formatInr(100800)).toBe("₹1,00,800");
    expect(formatInr(0)).toBe("₹0");
  });
});

describe("formatDate", () => {
  it("formats an ISO date without timezone shifts", () => {
    expect(formatDate("2026-07-20", "en")).toBe("20 Jul 2026");
    expect(formatDate(null, "en")).toBe("");
  });
});

describe("maskGstin", () => {
  it("keeps the first 5 and last 3 characters", () => {
    expect(maskGstin("32TTNXB7384T1ZW")).toBe("32TTN*****1ZW");
    expect(maskGstin("32TTNXB7384T1ZW")).not.toContain("XB7384T");
  });
  it("handles short, empty and already-masked values", () => {
    expect(maskGstin("ABC")).toBe("*****");
    expect(maskGstin(undefined)).toBe("");
    expect(maskGstin("32TTN*****1ZW")).toBe("32TTN*****1ZW");
  });
});
