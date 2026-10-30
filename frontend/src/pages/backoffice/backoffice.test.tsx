import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../api/backoffice";
import { ApiError, NetworkError } from "../../api/client";
import { App } from "../../App";
import { clearBackofficeKey, readBackofficeKey, saveBackofficeKey } from "../../lib/backoffice";
import { insights, leadDetail, queuePage, queueRow, reengagement } from "../../test/backofficeFixtures";

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
  return userEvent.setup();
}

const UNAUTHORIZED = new ApiError(401, "A valid back-office key is required", "BACKOFFICE_KEY_REQUIRED");

afterEach(() => {
  vi.restoreAllMocks();
  clearBackofficeKey();
});

describe("passcode gate", () => {
  it("sends you to the gate without a key, and calls nothing", () => {
    const queue = vi.spyOn(api, "getQueue");
    renderAt("/backoffice");
    expect(screen.getByLabelText("Passcode")).toBeInTheDocument();
    expect(screen.getByText("Prototype access")).toBeInTheDocument();
    expect(queue).not.toHaveBeenCalled();
  });

  it.each(["/backoffice/insights", "/backoffice/leads/SESSION/abc"])("guards %s too", (path) => {
    renderAt(path);
    expect(screen.getByLabelText("Passcode")).toBeInTheDocument();
  });

  it("keeps a right passcode for the session and opens the queue", async () => {
    const check = vi.spyOn(api, "checkKey").mockResolvedValue(queuePage());
    vi.spyOn(api, "getQueue").mockResolvedValue(queuePage());
    const user = renderAt("/backoffice/login");
    await user.type(screen.getByLabelText("Passcode"), "open-sesame");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("heading", { name: "Call queue" })).toBeInTheDocument();
    expect(check).toHaveBeenCalledWith("open-sesame");
    expect(readBackofficeKey()).toBe("open-sesame");
  });

  it("refuses a wrong passcode and doesn't keep it", async () => {
    vi.spyOn(api, "checkKey").mockRejectedValue(UNAUTHORIZED);
    const user = renderAt("/backoffice/login");
    await user.type(screen.getByLabelText("Passcode"), "wrong");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("isn't right");
    expect(readBackofficeKey()).toBeNull();
  });

  it("asks for something before trying", async () => {
    const check = vi.spyOn(api, "checkKey");
    const user = renderAt("/backoffice/login");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter the passcode");
    expect(check).not.toHaveBeenCalled();
  });

  it("says when the server can't be reached rather than blaming the passcode", async () => {
    vi.spyOn(api, "checkKey").mockRejectedValue(new NetworkError());
    const user = renderAt("/backoffice/login");
    await user.type(screen.getByLabelText("Passcode"), "x");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach the server");
  });

  it("returns to the gate when a saved key stops working", async () => {
    saveBackofficeKey("old");
    vi.spyOn(api, "getQueue").mockRejectedValue(UNAUTHORIZED);
    renderAt("/backoffice");
    expect(await screen.findByLabelText("Passcode")).toBeInTheDocument();
    expect(readBackofficeKey()).toBeNull();
  });

  it("signing out forgets the key", async () => {
    saveBackofficeKey("k");
    vi.spyOn(api, "getQueue").mockResolvedValue(queuePage());
    const user = renderAt("/backoffice");
    await user.click(await screen.findByRole("button", { name: "Sign out" }));
    expect(screen.getByLabelText("Passcode")).toBeInTheDocument();
    expect(readBackofficeKey()).toBeNull();
  });

  it("doesn't wrap the customer app in the back office", () => {
    renderAt("/track");
    expect(screen.queryByText("Prototype access")).not.toBeInTheDocument();
  });
});

describe("queue", () => {
  beforeEach(() => {
    saveBackofficeKey("k");
  });

  it("shows the band, score, top reason, action and channel of each lead", async () => {
    vi.spyOn(api, "getQueue").mockResolvedValue(queuePage([queueRow({ band: "HOT", score: 91 })]));
    renderAt("/backoffice");
    const row = (await screen.findByRole("link", { name: "Dental clinic" })).closest("tr") as HTMLElement;
    expect(within(row).getByText("Hot")).toBeInTheDocument();
    expect(within(row).getByText("91/100")).toBeInTheDocument();
    expect(within(row).getByText(/Active GSTIN/)).toBeInTheDocument();
    expect(within(row).getByText("Follow up on the quote")).toBeInTheDocument();
    expect(within(row).getByText("Inside sales")).toBeInTheDocument();
    expect(within(row).getByText("Advisor: Ready for checkout")).toBeInTheDocument();
  });

  it("tabs and filters ask the server for the right rows", async () => {
    const queue = vi.spyOn(api, "getQueue").mockResolvedValue(queuePage());
    const user = renderAt("/backoffice");
    await screen.findByRole("link", { name: "Dental clinic" });
    expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ kind: undefined, page: 0, size: 25 }), expect.anything());

    await user.click(screen.getByRole("tab", { name: "Stuck orders" }));
    await waitFor(() => expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ kind: "ORDER" }), expect.anything()));

    await user.selectOptions(screen.getByLabelText("Band"), "HOT");
    await waitFor(() => expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ kind: "ORDER", band: "HOT" }), expect.anything()));

    await user.selectOptions(screen.getByLabelText("Segment"), "Micro retail & services");
    await waitFor(() => expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ segment: "Micro retail & services", band: "HOT" }), expect.anything()));
  });

  it("pages through the results and resets to the first page on a new filter", async () => {
    const queue = vi.spyOn(api, "getQueue").mockResolvedValue(queuePage([queueRow()], 60));
    const user = renderAt("/backoffice");
    expect(await screen.findByText("1–25 of 60")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }), expect.anything()));
    expect(await screen.findByText("26–50 of 60")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Status"), "NEW");
    await waitFor(() => expect(queue).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, status: "NEW" }), expect.anything()));
  });

  it("the re-engage tab shows counts per reason and hides the filters", async () => {
    vi.spyOn(api, "getQueue").mockResolvedValue(queuePage());
    const reengage = vi.spyOn(api, "getReengagement").mockResolvedValue(reengagement);
    const user = renderAt("/backoffice");
    await user.click(await screen.findByRole("tab", { name: "Re-engage history" }));
    expect(await screen.findByText("Owner had no time for a visit")).toBeInTheDocument();
    expect(screen.getByText("531")).toBeInTheDocument();
    expect(screen.getByText(/1,291 leads were lost/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Balaji Dental Care LLP" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Band")).not.toBeInTheDocument();
    expect(reengage).toHaveBeenCalledWith(0, 25, expect.anything());
  });

  it("offers a retry when loading fails", async () => {
    const queue = vi.spyOn(api, "getQueue").mockRejectedValueOnce(new NetworkError()).mockResolvedValue(queuePage());
    const user = renderAt("/backoffice");
    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach the server");
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("link", { name: "Dental clinic" })).toBeInTheDocument();
    expect(queue).toHaveBeenCalledTimes(2);
  });

  it("says so when nothing matches", async () => {
    vi.spyOn(api, "getQueue").mockResolvedValue(queuePage([]));
    renderAt("/backoffice");
    expect(await screen.findByText("No leads match these filters.")).toBeInTheDocument();
  });
});

describe("lead detail", () => {
  beforeEach(() => {
    saveBackofficeKey("k");
  });

  const PATH = "/backoffice/leads/SESSION/11111111-1111-1111-1111-111111111111";

  it("shows the reasons, quote, chat and why this action", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(leadDetail());
    renderAt(PATH);
    expect(await screen.findByRole("heading", { name: "Why this score" })).toBeInTheDocument();
    expect(screen.getByText(/didn't proceed/)).toBeInTheDocument();
    expect(screen.getByText("We need internet for the clinic")).toBeInTheDocument();
    expect(screen.getByText(/2,499\/month, ₹500 one-time, 12-month contract/)).toBeInTheDocument();
    expect(screen.getByText("32TTN*****1ZW")).toBeInTheDocument();
    expect(screen.getByText(/Contact details aren't collected/)).toBeInTheDocument();
  });

  it("shows failed KYC checks on an order", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(
      leadDetail({
        lead: queueRow({ kind: "ORDER", suggestedAction: "KYC_HELP", channelWhy: "Help them fix this: address proof too old." }),
        recentMessages: [],
        failedChecks: [{ code: "C14", messageEn: "The address proof is 164 days old; it must be at most 90 days old.", messageHi: "", fixHintEn: "Upload a utility bill from the last 90 days.", fixHintHi: "" }],
      }),
    );
    renderAt(PATH);
    expect(await screen.findByText(/C14: The address proof/)).toBeInTheDocument();
    expect(screen.getByText("Help fix KYC")).toBeInTheDocument();
    expect(screen.getByText(/Upload a utility bill/)).toBeInTheDocument();
  });

  it("saves an assignment and shows it in the history", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(leadDetail());
    const assign = vi.spyOn(api, "assignLead").mockResolvedValue(
      leadDetail({ history: [{ channel: "CHANNEL_PARTNER", status: "NEW", assigneeLabel: "Priya", note: "Call after 5", createdAt: "2026-07-15T10:00:00Z" }] }),
    );
    const user = renderAt(PATH);
    await user.selectOptions(await screen.findByLabelText("Channel"), "CHANNEL_PARTNER");
    await user.type(screen.getByLabelText("Assignee (optional)"), "Priya");
    await user.type(screen.getByLabelText("Note (optional)"), "Call after 5");
    await user.click(screen.getByRole("button", { name: "Save assignment" }));
    expect(assign).toHaveBeenCalledWith("SESSION", "11111111-1111-1111-1111-111111111111", { channel: "CHANNEL_PARTNER", assigneeLabel: "Priya", note: "Call after 5" });
    expect(await screen.findByText("Assignment saved.")).toBeInTheDocument();
    expect(screen.getByText("Call after 5")).toBeInTheDocument();
  });

  it("saves a status change", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(leadDetail());
    const status = vi.spyOn(api, "setLeadStatus").mockResolvedValue(leadDetail());
    const user = renderAt(PATH);
    await user.selectOptions(await screen.findByLabelText("Status"), "CONTACTED");
    await user.click(screen.getByRole("button", { name: "Save status" }));
    expect(status).toHaveBeenCalledWith("SESSION", "11111111-1111-1111-1111-111111111111", { status: "CONTACTED", note: undefined });
  });

  it("doesn't offer field sales for a Micro lead", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(leadDetail({ lead: queueRow({ segment: "Micro retail & services", recommendedChannel: "DIGITAL_NUDGE" }) }));
    renderAt(PATH);
    const channel = await screen.findByLabelText("Channel");
    expect(within(channel).getByRole("option", { name: "Field sales" })).toBeDisabled();
    expect(screen.getByText(/isn't available for Micro leads/)).toBeInTheDocument();
  });

  it("shows the server's refusal", async () => {
    vi.spyOn(api, "getLead").mockResolvedValue(leadDetail());
    vi.spyOn(api, "assignLead").mockRejectedValue(new ApiError(400, "Field visits cost about ₹53k per Micro activation", "MICRO_NO_FIELD_SALES"));
    const user = renderAt(PATH);
    await user.click(await screen.findByRole("button", { name: "Save assignment" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("₹53k");
  });
});

describe("insights", () => {
  beforeEach(() => {
    saveBackofficeKey("k");
  });

  it("renders every card from the numbers it's given", async () => {
    vi.spyOn(api, "getInsights").mockResolvedValue(insights);
    renderAt("/backoffice/insights");
    expect(await screen.findByRole("heading", { name: "Insights" })).toBeInTheDocument();
    for (const value of ["5,000 (100.0%)", "3,406 (68.1%)", "1,974 (39.5%)", "861 (17.2%)", "588 (11.8%)", "368 (7.4%)"]) {
      expect(screen.getByText(value)).toBeInTheDocument();
    }
    const micro = screen.getByRole("row", { name: /Micro retail/ });
    expect(within(micro).getByText("110")).toBeInTheDocument();
    expect(within(micro).getByText(/8\.0 months/)).toBeInTheDocument();
    expect(within(micro).getByText("(longest)")).toBeInTheDocument();
    expect(screen.getByText("48 of 368")).toBeInTheDocument();
    expect(screen.getByText(/activated bundles broke catalogue rules/)).toBeInTheDocument();
    expect(screen.getByText("The top 20% of leads by score hold 37.5% of activations.")).toBeInTheDocument();
    expect(screen.getByText("0.699")).toBeInTheDocument();
    expect(screen.getByText("0.714")).toBeInTheDocument();
    expect(screen.getByText("Referral")).toBeInTheDocument();
    expect(screen.getByText("KYC verified")).toBeInTheDocument();
  });

  it("can read the lift chart as a table", async () => {
    vi.spyOn(api, "getInsights").mockResolvedValue(insights);
    renderAt("/backoffice/insights");
    expect(await screen.findByText("Show as a table")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "2.04" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "37.5%" })).toBeInTheDocument();
  });
});
