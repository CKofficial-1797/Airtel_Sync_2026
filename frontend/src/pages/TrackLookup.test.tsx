import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/client";
import * as trackApi from "../api/track";
import { TrackLookup } from "./TrackLookup";

function setup() {
  render(
    <MemoryRouter initialEntries={["/track"]}>
      <Routes>
        <Route path="/track" element={<TrackLookup />} />
        <Route path="/track/:orderId" element={<p>Tracker page</p>} />
      </Routes>
    </MemoryRouter>,
  );
  return userEvent.setup();
}

async function fill(user: ReturnType<typeof userEvent.setup>, ref: string, pin: string) {
  if (ref) await user.type(screen.getByLabelText("Order reference"), ref);
  if (pin) await user.type(screen.getByLabelText("Installation PIN code"), pin);
  await user.click(screen.getByRole("button", { name: "Find my order" }));
}

describe("TrackLookup", () => {
  afterEach(() => vi.restoreAllMocks());

  it("checks the order reference and PIN code format before asking the server", async () => {
    const lookup = vi.spyOn(trackApi, "lookupOrder");
    const user = setup();
    await fill(user, "12345", "41123");

    expect(screen.getByText(/Enter the reference as shown on your order/)).toBeInTheDocument();
    expect(screen.getByText("Enter your 6-digit PIN code.")).toBeInTheDocument();
    expect(screen.getByLabelText("Order reference")).toHaveAttribute("aria-invalid", "true");
    expect(lookup).not.toHaveBeenCalled();
  });

  it.each([
    ["UD-260715", "411231", "reference"],
    ["UD-260715-42", "411231", "reference"],
    ["UD-260715-0042", "ABCDEF", "pin"],
    ["UD-260715-0042", "41123", "pin"],
  ])("rejects %s / %s", async (ref, pin, bad) => {
    const lookup = vi.spyOn(trackApi, "lookupOrder");
    const user = setup();
    await fill(user, ref, pin);
    expect(lookup).not.toHaveBeenCalled();
    expect(screen.getByLabelText(bad === "reference" ? "Order reference" : "Installation PIN code")).toHaveAttribute("aria-invalid", "true");
  });

  it("opens the tracker for a match, accepting lower case and spaces", async () => {
    const lookup = vi.spyOn(trackApi, "lookupOrder").mockResolvedValue({ orderId: "abc" });
    const user = setup();
    await fill(user, " ud-260715-0042 ", "411231");

    expect(await screen.findByText("Tracker page")).toBeInTheDocument();
    expect(lookup).toHaveBeenCalledWith("UD-260715-0042", "411231");
  });

  it("gives one friendly message for anything not found", async () => {
    vi.spyOn(trackApi, "lookupOrder").mockRejectedValue(new ApiError(404, "Order not found"));
    const user = setup();
    await fill(user, "UD-260715-0042", "000000");

    expect(await screen.findByRole("alert")).toHaveTextContent("Order not found. Please check the details and try again.");
    expect(screen.getByRole("button", { name: "Find my order" })).toBeEnabled();
  });

  it("explains the rate limit", async () => {
    vi.spyOn(trackApi, "lookupOrder").mockRejectedValue(new ApiError(429, "slow down", "RATE_LIMITED"));
    const user = setup();
    await fill(user, "UD-260715-0042", "000000");
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
  });

  it("reports a network problem as such", async () => {
    vi.spyOn(trackApi, "lookupOrder").mockRejectedValue(new NetworkError());
    const user = setup();
    await fill(user, "UD-260715-0042", "411231");
    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach the server");
  });
});
