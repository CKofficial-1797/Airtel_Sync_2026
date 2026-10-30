import { afterEach, describe, expect, it, vi } from "vitest";
import { clearBackofficeKey, formatStage, queueQueryString, readBackofficeKey, saveBackofficeKey } from "./backoffice";

describe("queueQueryString", () => {
  it("is empty when nothing is filtered", () => {
    expect(queueQueryString({})).toBe("");
    expect(queueQueryString({ kind: undefined, segment: "", band: "", page: 0 })).toBe("");
  });

  it("sends only the filters that are set", () => {
    expect(queueQueryString({ kind: "SESSION", band: "HOT", tier: "1", channel: "", status: "NEW", page: 2, size: 25 })).toBe(
      "?kind=SESSION&band=HOT&tier=1&status=NEW&page=2&size=25",
    );
  });

  it("encodes a segment name with an ampersand", () => {
    const query = queueQueryString({ segment: "Micro retail & services" });
    expect(new URLSearchParams(query).get("segment")).toBe("Micro retail & services");
    expect(query).toContain("%26");
  });

  it("can ask for the pure score ranking", () => {
    expect(queueQueryString({ sort: "score" })).toBe("?sort=score");
  });
});

describe("formatStage", () => {
  it("makes status codes readable", () => {
    expect(formatStage("KYC_ACTION_REQUIRED")).toBe("KYC action required");
    expect(formatStage("ON_HOLD: SITE_NOT_FEASIBLE")).toBe("On hold: Site not feasible");
    expect(formatStage("Advisor: READY_FOR_CHECKOUT")).toBe("Advisor: Ready for checkout");
  });

  it("leaves text from the data alone", () => {
    expect(formatStage("Contacted: Price too high")).toBe("Contacted: Price too high");
  });
});

describe("back-office key", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    clearBackofficeKey();
  });

  it("stays for the session and goes on sign-out", () => {
    expect(saveBackofficeKey("abc")).toBe(true);
    expect(readBackofficeKey()).toBe("abc");
    clearBackofficeKey();
    expect(readBackofficeKey()).toBeNull();
  });

  it("copes with blocked storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(readBackofficeKey()).toBeNull();
    expect(saveBackofficeKey("abc")).toBe(false);
    expect(() => clearBackofficeKey()).not.toThrow();
  });
});
