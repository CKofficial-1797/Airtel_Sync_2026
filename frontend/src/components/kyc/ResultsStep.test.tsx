import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { KycView } from "../../api/types";
import i18n from "../../i18n";
import k02Json from "../../test/fixtures/kyc-k02.json";
import k03Json from "../../test/fixtures/kyc-k03.json";
import k06Json from "../../test/fixtures/kyc-k06.json";
import k11Json from "../../test/fixtures/kyc-k11.json";
import { ResultsStep } from "./ResultsStep";

const k02 = k02Json as unknown as KycView;
const k03 = k03Json as unknown as KycView;
const k06 = k06Json as unknown as KycView;
const k11 = k11Json as unknown as KycView;

function setup(view: KycView) {
  const onFix = vi.fn();
  const onCallback = vi.fn();
  render(
    <MemoryRouter>
      <ResultsStep view={view} onFix={onFix} onCallback={onCallback} />
    </MemoryRouter>,
  );
  return { onFix, onCallback };
}

const reuploadButtons = () => screen.queryAllByRole("button", { name: /Re-upload|Upload |दोबारा अपलोड/ });

describe("ResultsStep", () => {
  afterEach(() => void i18n.changeLanguage("en"));

  it("VERIFIED (K03): green header, activation date, Track link, no fix buttons", () => {
    setup(k03);
    const header = screen.getByTestId("status-header");
    expect(header).toHaveTextContent("Documents verified ✓");
    expect(header).toHaveTextContent("Expected activation by 20 Jul 2026");
    expect(screen.getByRole("link", { name: "Track my order" })).toHaveAttribute("href", `/track/${k03.orderId}`);
    expect(reuploadButtons()).toHaveLength(0);
    expect(screen.queryByTestId("ocr-note")).not.toBeInTheDocument();
  });

  it("VERIFIED with OCR correction (K02): shows the correction note", () => {
    setup(k02);
    expect(screen.getByTestId("ocr-note")).toHaveTextContent("Corrected automatically");
    expect(screen.getByTestId("ocr-note")).toHaveTextContent("19E0G*****1ZL");
    expect(screen.getByTestId("ocr-note")).toHaveTextContent("19EOG*****1ZL");
  });

  it("ACTION_REQUIRED (K11): counts the fix, shows hint and a working re-upload button", async () => {
    const { onFix } = setup(k11);
    expect(screen.getByTestId("status-header")).toHaveTextContent("Almost there: 1 thing to fix");

    const toFix = within(screen.getByRole("region", { name: "To fix" }));
    const check = toFix.getByTestId("check-C14");
    expect(check).toHaveTextContent("257 days old");
    expect(check).toHaveTextContent("Upload a utility bill from the last 90 days.");

    await userEvent.setup().click(toFix.getByRole("button", { name: "Re-upload address proof" }));
    expect(onFix).toHaveBeenCalledWith("ADDRESS_PROOF");
    expect(screen.queryByRole("button", { name: "Request a call back" })).not.toBeInTheDocument();
  });

  it("REJECTED (K06): plain reason, hint, call back, and no re-upload buttons", async () => {
    const { onCallback } = setup(k06);
    const header = screen.getByTestId("status-header");
    expect(header).toHaveTextContent("We couldn't verify your business");
    expect(header).toHaveTextContent("The GSTIN on the certificate doesn't match official records.");

    expect(reuploadButtons()).toHaveLength(0);
    expect(screen.queryByRole("link", { name: "Track my order" })).not.toBeInTheDocument();

    const why = within(screen.getByRole("region", { name: "Why, and what you can do" }));
    expect(why.getByTestId("check-C04")).toHaveTextContent("08YKZ*****1Z0");

    await userEvent.setup().click(screen.getByRole("button", { name: "Request a call back" }));
    expect(onCallback).toHaveBeenCalled();
  });

  it("REJECTED with a fixable problem too: reports it, still without a re-upload loop", () => {
    const k01Like: KycView = {
      ...k06,
      checks: k06.checks.map((c) =>
        c.code === "C08" ? { ...c, status: "FAIL", fixable: true, messageEn: "The Udyam registration number is blank on the certificate." } : c,
      ),
    };
    setup(k01Like);
    expect(screen.getByText("We also noticed")).toBeInTheDocument();
    expect(screen.getAllByText(/Udyam registration number is blank/).length).toBeGreaterThan(0);
    expect(reuploadButtons()).toHaveLength(0);
  });

  it("uses the Hindi messages and hints when the UI is in Hindi", async () => {
    await i18n.changeLanguage("hi");
    setup(k11);
    const c14 = k11.checks.find((c) => c.code === "C14")!;
    const item = within(screen.getByRole("region", { name: "ठीक करना है" })).getByTestId("check-C14");
    expect(item).toHaveTextContent(c14.messageHi);
    expect(item).toHaveTextContent(c14.fixHintHi!);
    expect(item).not.toHaveTextContent(c14.messageEn);
    expect(screen.getByRole("button", { name: "पते का प्रमाण दोबारा अपलोड करें" })).toBeInTheDocument();
  });

  it("lists warnings neutrally and all checks with a text status, not colour alone", () => {
    setup(k11);
    const notes = within(screen.getByRole("region", { name: "Good to know" }));
    expect(notes.getByTestId("check-C11")).toHaveTextContent("Note:");
    expect(notes.getByTestId("check-C11")).toHaveTextContent("board resolution");

    const passed = k11.checks.filter((c) => c.status === "PASS").length;
    expect(screen.getByText(`All checks (${passed} passed)`)).toBeInTheDocument();
    expect(screen.getAllByText("Passed:")).toHaveLength(passed);
    expect(screen.getAllByText("Problem:").length).toBeGreaterThan(0);
  });
});
