import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client";
import * as trackApi from "../../api/track";
import type { TrackerView } from "../../api/types";
import onHold from "../../test/fixtures/tracker-on-hold.json";
import scheduled from "../../test/fixtures/tracker-install-scheduled.json";
import { DemoPanel } from "./DemoPanel";

const SCHEDULED = scheduled as unknown as TrackerView;
const ON_HOLD = onHold as unknown as TrackerView;

function setup(url: string, view: TrackerView = SCHEDULED, onResult = vi.fn()) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <DemoPanel view={view} onResult={onResult} />
    </MemoryRouter>,
  );
  return onResult;
}

describe("DemoPanel", () => {
  afterEach(() => vi.restoreAllMocks());

  it("is hidden without ?demo=1, and doesn't even ask the server", async () => {
    const status = vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    setup("/track/o1");
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId("demo-panel")).not.toBeInTheDocument();
    expect(status).not.toHaveBeenCalled();
  });

  it("is hidden with ?demo=1 when the backend's demo mode is off", async () => {
    const status = vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: false });
    setup("/track/o1?demo=1");
    await waitFor(() => expect(status).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId("demo-panel")).not.toBeInTheDocument();
  });

  it("is hidden when the status can't be read", async () => {
    vi.spyOn(trackApi, "getDemoStatus").mockRejectedValue(new Error("down"));
    setup("/track/o1?demo=1");
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId("demo-panel")).not.toBeInTheDocument();
  });

  it("needs exactly demo=1", async () => {
    const status = vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    setup("/track/o1?demo=true");
    await new Promise((r) => setTimeout(r, 20));
    expect(status).not.toHaveBeenCalled();
    expect(screen.queryByTestId("demo-panel")).not.toBeInTheDocument();
  });

  it("shows clearly labelled controls with both, and advances", async () => {
    vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    const advance = vi.spyOn(trackApi, "demoAdvance").mockResolvedValue(ON_HOLD);
    const onResult = setup("/track/o1?demo=1");

    expect(await screen.findByRole("heading", { name: "Demo controls" })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Advance to next step" }));
    expect(advance).toHaveBeenCalledWith(SCHEDULED.orderId, {});
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(ON_HOLD));
  });

  it("puts on hold, and only offers Resume while on hold", async () => {
    vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    const advance = vi.spyOn(trackApi, "demoAdvance").mockResolvedValue(ON_HOLD);
    setup("/track/o1?demo=1");
    await screen.findByTestId("demo-panel");

    expect(screen.getByRole("button", { name: "Resume" })).toBeDisabled();
    await userEvent.setup().click(screen.getByRole("button", { name: "Put on hold: site not feasible" }));
    expect(advance).toHaveBeenCalledWith(SCHEDULED.orderId, { onHoldReason: "SITE_NOT_FEASIBLE" });
  });

  it("resumes an order on hold", async () => {
    vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    const advance = vi.spyOn(trackApi, "demoAdvance").mockResolvedValue(SCHEDULED);
    setup("/track/o1?demo=1", ON_HOLD);
    await screen.findByTestId("demo-panel");

    expect(screen.getByRole("button", { name: "Advance to next step" })).toBeDisabled();
    await userEvent.setup().click(screen.getByRole("button", { name: "Resume" }));
    expect(advance).toHaveBeenCalledWith(ON_HOLD.orderId, {});
  });

  it("shows why a move was refused", async () => {
    vi.spyOn(trackApi, "getDemoStatus").mockResolvedValue({ enabled: true });
    vi.spyOn(trackApi, "demoAdvance").mockRejectedValue(new ApiError(409, "Demo controls start after the documents are verified", "INVALID_TRANSITION"));
    const onResult = setup("/track/o1?demo=1");
    await screen.findByTestId("demo-panel");

    await userEvent.setup().click(screen.getByRole("button", { name: "Advance to next step" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("start after the documents are verified");
    expect(onResult).not.toHaveBeenCalled();
  });
});
