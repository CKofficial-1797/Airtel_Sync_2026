import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client";
import * as trackApi from "../../api/track";
import type { InstallSlots, TrackerView } from "../../api/types";
import slots from "../../test/fixtures/install-slots.json";
import verified from "../../test/fixtures/tracker-kyc-verified.json";
import { SlotPicker } from "./SlotPicker";

const OPTIONS = slots as unknown as InstallSlots;
const confirmButton = () => screen.getByRole("button", { name: "Confirm slot" });

describe("SlotPicker", () => {
  beforeEach(() => {
    vi.spyOn(trackApi, "getInstallSlots").mockResolvedValue(OPTIONS);
  });
  afterEach(() => vi.restoreAllMocks());

  async function setup(onBooked = vi.fn()) {
    render(<SlotPicker orderId="o1" onBooked={onBooked} />);
    await screen.findByTestId("date-2026-07-20");
    return onBooked;
  }

  it("offers only the server's window, starting at the ETA", async () => {
    await setup();
    expect(screen.getAllByRole("button", { pressed: false }).length).toBe(OPTIONS.dates.length + 3);
    expect(screen.queryByTestId("date-2026-07-19")).not.toBeInTheDocument();
    expect(screen.queryByTestId("date-2026-08-04")).not.toBeInTheDocument();
  });

  it("disables Sundays and can't select them", async () => {
    await setup();
    const user = userEvent.setup();
    const sunday = screen.getByTestId("date-2026-07-26");
    expect(sunday).toBeDisabled();
    expect(screen.getByTestId("date-2026-08-02")).toBeDisabled();
    await user.click(sunday);
    expect(sunday).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("We don't install on Sundays.")).toBeInTheDocument();
  });

  it("needs both a date and a time before it can confirm", async () => {
    await setup();
    const user = userEvent.setup();
    expect(confirmButton()).toBeDisabled();
    await user.click(screen.getByTestId("date-2026-07-22"));
    expect(confirmButton()).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Afternoon" }));
    expect(confirmButton()).toBeEnabled();
  });

  it("sends the chosen date and slot and hands back the new tracker", async () => {
    const book = vi.spyOn(trackApi, "bookInstallSlot").mockResolvedValue(verified as unknown as TrackerView);
    const onBooked = await setup();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("date-2026-07-22"));
    await user.click(screen.getByRole("button", { name: "Evening" }));
    await user.click(confirmButton());

    expect(book).toHaveBeenCalledWith("o1", "2026-07-22", "EVENING");
    expect(onBooked).toHaveBeenCalledWith(verified);
  });

  it("starts from the booked slot when rescheduling", async () => {
    vi.mocked(trackApi.getInstallSlots).mockResolvedValue({ ...OPTIONS, current: { date: "2026-07-24", slot: "MORNING" } });
    await setup();
    expect(screen.getByTestId("date-2026-07-24")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Morning" })).toHaveAttribute("aria-pressed", "true");
    expect(confirmButton()).toBeEnabled();
  });

  it("explains a slot the server refuses and lets the owner pick again", async () => {
    vi.spyOn(trackApi, "bookInstallSlot").mockRejectedValue(new ApiError(400, "bad", "INVALID_SLOT"));
    const onBooked = await setup();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("date-2026-07-22"));
    await user.click(screen.getByRole("button", { name: "Morning" }));
    await user.click(confirmButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("isn't available any more");
    expect(onBooked).not.toHaveBeenCalled();
    expect(confirmButton()).toBeEnabled();
  });

  it("offers a retry when the dates can't be loaded", async () => {
    vi.mocked(trackApi.getInstallSlots).mockRejectedValueOnce(new Error("down"));
    render(<SlotPicker orderId="o1" onBooked={vi.fn()} />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Retry" }));
    expect(await screen.findByTestId("date-2026-07-20")).toBeInTheDocument();
  });
});
