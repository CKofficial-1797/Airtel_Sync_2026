import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as trackApi from "../api/track";
import type { InstallSlots, TrackerView } from "../api/types";
import i18n from "../i18n";
import slots from "../test/fixtures/install-slots.json";
import actionRequired from "../test/fixtures/tracker-action-required.json";
import active from "../test/fixtures/tracker-active.json";
import scheduled from "../test/fixtures/tracker-install-scheduled.json";
import verified from "../test/fixtures/tracker-kyc-verified.json";
import onHold from "../test/fixtures/tracker-on-hold.json";
import rejected from "../test/fixtures/tracker-rejected.json";
import { Track } from "./Track";

const asView = (json: unknown) => json as TrackerView;

async function open(json: unknown) {
  vi.spyOn(trackApi, "getTracker").mockResolvedValue(asView(json));
  vi.spyOn(trackApi, "getInstallSlots").mockResolvedValue(slots as unknown as InstallSlots);
  const view = asView(json);
  render(
    <MemoryRouter initialEntries={[`/track/${view.orderId}`]}>
      <Routes>
        <Route path="/track/:orderId" element={<Track />} />
      </Routes>
    </MemoryRouter>,
  );
  await screen.findByTestId("tracker-header");
  return view;
}

const stepText = (key: string) => screen.getByTestId(`step-${key}`).textContent;

function expectSteps(expected: Record<string, string>) {
  for (const [key, word] of Object.entries(expected)) {
    expect(stepText(key), key).toContain(word);
  }
}

describe("Track page", () => {
  beforeEach(() => void i18n.changeLanguage("en"));
  afterEach(() => {
    vi.restoreAllMocks();
    void i18n.changeLanguage("en");
  });

  it("KYC_VERIFIED: step 2 done, per-item dates and the slot picker", async () => {
    await open(verified);

    expectSteps({ ORDER_PLACED: "Done", DOCUMENTS: "Done", SCHEDULED: "In progress", INSTALLED: "Coming up", ACTIVE: "Coming up" });
    expect(stepText("DOCUMENTS")).toContain("15 Jul 2026");

    const eta = within(screen.getByTestId("eta-card"));
    expect(eta.getByText("By 20 Jul 2026")).toBeInTheDocument();
    expect(eta.getByText(/Business Postpaid 299 ×2/)).toBeInTheDocument();
    expect(eta.getByText("16 Jul 2026")).toBeInTheDocument();
    expect(eta.getByText("Biz Fibre 100")).toBeInTheDocument();

    expect(screen.getByTestId("slot-card")).toHaveTextContent("Pick your installation slot");
    expect(await screen.findByTestId("date-2026-07-20")).toBeEnabled();
    expect(screen.getByTestId("tracker-header")).toHaveTextContent("Ganesh Woodcraft");
    expect(screen.getByTestId("tracker-header")).toHaveTextContent("₹1,597");
    expect(screen.queryByTestId("reassurance")).not.toBeInTheDocument();
  });

  it("INSTALL_SCHEDULED: the slot, a way to change it and reassurance", async () => {
    await open(scheduled);

    expectSteps({ SCHEDULED: "Done", INSTALLED: "In progress", ACTIVE: "Coming up" });
    expect(stepText("INSTALLED")).toContain("planned 22 Jul 2026");
    expect(screen.getByTestId("booked-card")).toHaveTextContent("22 Jul 2026 · Afternoon");
    expect(screen.getByRole("button", { name: "Change slot" })).toBeInTheDocument();
    expect(screen.getByTestId("reassurance")).toHaveTextContent("Nothing to do");
    expect(screen.queryByTestId("slot-card")).not.toBeInTheDocument();
  });

  it("ON_HOLD: a calm banner, the blocked step and no stale date", async () => {
    await open(onHold);

    const banner = screen.getByTestId("hold-banner");
    expect(banner).toHaveTextContent("Your order is on hold");
    expect(banner).toHaveTextContent("Fibre isn't available at your address yet.");
    expect(banner).toHaveTextContent("You don't need to call us.");
    expectSteps({ SCHEDULED: "Done", INSTALLED: "On hold" });
    expect(stepText("INSTALLED")).not.toContain("Needs attention");
    expect(stepText("INSTALLED")).not.toContain("22 Jul");
    expect(screen.getByTestId("eta-card")).toHaveTextContent("New date once this clears");
    expect(screen.queryByTestId("reassurance")).not.toBeInTheDocument();
    expect(screen.queryByTestId("slot-card")).not.toBeInTheDocument();
  });

  it("KYC_ACTION_REQUIRED: step 2 blocked, the reason and a link to fix it", async () => {
    const view = await open(actionRequired);

    expectSteps({ ORDER_PLACED: "Done", DOCUMENTS: "Needs attention", SCHEDULED: "Coming up" });
    expect(screen.getByTestId("blocked-card")).toHaveTextContent("257 days old");
    const link = screen.getByTestId("next-action-link");
    expect(link).toHaveTextContent("Fix documents");
    expect(link).toHaveAttribute("href", `/kyc/${view.orderId}`);
    expect(screen.getByTestId("eta-card")).toHaveTextContent("5 days after your documents are verified");
    expect(screen.queryByTestId("slot-card")).not.toBeInTheDocument();
  });

  it("KYC_REJECTED: step 2 blocked, an explanation, and nothing to do or pick", async () => {
    await open(rejected);

    expectSteps({ DOCUMENTS: "Needs attention", SCHEDULED: "Coming up" });
    const blocked = screen.getByTestId("blocked-card");
    expect(blocked).toHaveTextContent("08YKZ*****1Z0");
    expect(blocked).toHaveTextContent("support");
    expect(screen.queryByTestId("slot-card")).not.toBeInTheDocument();
    expect(screen.queryByTestId("next-action-link")).not.toBeInTheDocument();
    expect(screen.queryByTestId("eta-card")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reassurance")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Request a call back" })).toBeInTheDocument();
  });

  it("ACTIVE: every step done, the date it went live, nothing to do", async () => {
    await open(active);

    expectSteps({ ORDER_PLACED: "Done", DOCUMENTS: "Done", SCHEDULED: "Done", INSTALLED: "Done", ACTIVE: "Done" });
    expect(screen.getByTestId("eta-card")).toHaveTextContent("Active since 22 Jul 2026");
    expect(screen.getByTestId("reassurance")).toBeInTheDocument();
    expect(screen.getByText("Your services are active")).toBeInTheDocument();
    expect(screen.queryByTestId("booked-card")).not.toBeInTheDocument();
  });

  it("the activity list reads latest first", async () => {
    await open(scheduled);
    const items = within(screen.getByRole("region", { name: "Activity" })).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("Installation booked for 22 Jul 2026 (afternoon)");
    expect(items[items.length - 1]).toHaveTextContent("Order placed");
  });

  it("uses the server's Hindi and the Hindi interface", async () => {
    await i18n.changeLanguage("hi");
    await open(onHold);

    expect(screen.getByTestId("hold-banner")).toHaveTextContent("आपके पते पर अभी फ़ाइबर उपलब्ध नहीं है।");
    expect(stepText("ORDER_PLACED")).toContain("ऑर्डर हो गया");
    expect(stepText("ORDER_PLACED")).toContain("पूरा");
    expect(screen.getByTestId("eta-card")).toHaveTextContent("यह सुलझते ही नई तारीख मिलेगी");
  });

  it("shows every step's state as a word, not by colour alone", async () => {
    await open(onHold);
    for (const key of ["ORDER_PLACED", "DOCUMENTS", "SCHEDULED", "INSTALLED", "ACTIVE"]) {
      expect(stepText(key)).toMatch(/Done|In progress|Coming up|On hold/);
    }
  });

  it("explains an order that can't be found", async () => {
    const { ApiError } = await import("../api/client");
    vi.spyOn(trackApi, "getTracker").mockRejectedValue(new ApiError(404, "Order not found"));
    render(
      <MemoryRouter initialEntries={["/track/x"]}>
        <Routes>
          <Route path="/track/:orderId" element={<Track />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find this order.");
    expect(screen.getByRole("link", { name: "Find my order" })).toHaveAttribute("href", "/track");
  });

  it("every fixture is free of identifiers", () => {
    for (const fixture of [verified, scheduled, onHold, actionRequired, rejected, active]) {
      const json = JSON.stringify(fixture);
      expect(json).not.toMatch(/\d{2}[A-Z0-9]{5}\d{4}[A-Z0-9]{4}/);
      expect(json).not.toMatch(/UDYAM-[A-Z]{2}-\d{2}-\d{7}/);
      expect(json).not.toContain("SYN-ID");
    }
  });
});
