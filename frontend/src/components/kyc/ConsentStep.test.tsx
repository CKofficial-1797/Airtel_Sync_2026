import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kycApi from "../../api/kyc";
import type { ConsentNotice } from "../../api/types";
import notice from "../../test/fixtures/notice-en.json";
import { ConsentStep } from "./ConsentStep";

const agreeButton = () => screen.getByRole("button", { name: "Agree & continue" });

async function renderStep(onAgree = vi.fn(async () => undefined)) {
  render(<ConsentStep onAgree={onAgree} />);
  await screen.findByRole("button", { name: "Agree & continue" });
  return onAgree;
}

function box(code: string): HTMLElement {
  const purpose = notice.purposes.find((p) => p.code === code)!;
  return screen.getByRole("checkbox", { name: new RegExp(purpose.title) });
}

describe("ConsentStep", () => {
  beforeEach(() => {
    vi.spyOn(kycApi, "getConsentNotice").mockResolvedValue(notice as ConsentNotice);
  });
  afterEach(() => vi.restoreAllMocks());

  it("starts with nothing ticked, including the optional purpose", async () => {
    await renderStep();
    for (const box of screen.getAllByRole("checkbox")) {
      expect(box).not.toBeChecked();
    }
    expect(box("WHATSAPP_UPDATES")).not.toBeChecked();
  });

  it("stays disabled until every required purpose is ticked", async () => {
    await renderStep();
    const user = userEvent.setup();
    expect(agreeButton()).toBeDisabled();

    await user.click(box("KYC_VERIFICATION"));
    expect(agreeButton()).toBeDisabled();

    await user.click(box("WHATSAPP_UPDATES"));
    expect(agreeButton()).toBeDisabled();

    await user.click(box("SERVICE_INSTALLATION"));
    expect(agreeButton()).toBeEnabled();
  });

  it("sends the notice version, language and every purpose, optional one false", async () => {
    const onAgree = await renderStep();
    const user = userEvent.setup();
    await user.click(box("KYC_VERIFICATION"));
    await user.click(box("SERVICE_INSTALLATION"));
    await user.click(agreeButton());

    expect(onAgree).toHaveBeenCalledWith({
      noticeVersion: "2026-07-v2",
      language: "en",
      purposes: { KYC_VERIFICATION: true, SERVICE_INSTALLATION: true, WHATSAPP_UPDATES: false },
    });
  });

  it("sends the optional purpose as true when ticked", async () => {
    const onAgree = await renderStep();
    const user = userEvent.setup();
    await user.click(box("KYC_VERIFICATION"));
    await user.click(box("SERVICE_INSTALLATION"));
    await user.click(box("WHATSAPP_UPDATES"));
    await user.click(agreeButton());

    expect(onAgree.mock.calls[0]).toEqual([expect.objectContaining({ purposes: expect.objectContaining({ WHATSAPP_UPDATES: true }) })]);
  });

  it("marks purposes as required or optional and has the full notice", async () => {
    await renderStep();
    expect(screen.getAllByText("Required")).toHaveLength(2);
    expect(screen.getAllByText("Optional")).toHaveLength(1);
    expect(screen.getByText("Read full notice")).toBeInTheDocument();
    expect(screen.getByText(notice.retention)).toBeInTheDocument();
  });

  it("shows an error and lets the owner try again when saving fails", async () => {
    const onAgree = vi.fn(async () => {
      throw new Error("boom");
    });
    await renderStep(onAgree);
    const user = userEvent.setup();
    await user.click(box("KYC_VERIFICATION"));
    await user.click(box("SERVICE_INSTALLATION"));
    await user.click(agreeButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
    expect(agreeButton()).toBeEnabled();
  });
});
