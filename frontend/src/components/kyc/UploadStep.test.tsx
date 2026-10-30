import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { KycView } from "../../api/types";
import k11 from "../../test/fixtures/kyc-k11.json";
import { UploadStep } from "./UploadStep";

const K11 = k11 as unknown as KycView;

function setup(view: KycView) {
  render(<UploadStep view={view} focusSlot={null} submitting={false} submitError={null} onUpload={vi.fn()} onSubmit={vi.fn()} />);
}

describe("UploadStep", () => {
  it("can't re-check while the verdict stands: a document must change first", () => {
    setup(K11);
    expect(screen.getByRole("button", { name: "Check again" })).toBeDisabled();
    expect(screen.getByText("Upload the corrected document first, then check again.")).toBeInTheDocument();
  });

  it("can check again once a new document reopened the KYC", () => {
    setup({ ...K11, kycStatus: "KYC_IN_PROGRESS", orderStatus: "KYC_IN_PROGRESS" });
    expect(screen.getByRole("button", { name: "Check again" })).toBeEnabled();
    expect(screen.queryByText("Upload the corrected document first, then check again.")).not.toBeInTheDocument();
  });

  it("doesn't offer checking without all documents while a fix is pending", () => {
    setup({ ...K11, documents: K11.documents.slice(0, 3) });
    expect(screen.queryByRole("button", { name: "I don't have all documents" })).not.toBeInTheDocument();
  });

  it("offers it while the KYC is open", () => {
    setup({ ...K11, kycStatus: "KYC_IN_PROGRESS", documents: K11.documents.slice(0, 3) });
    expect(screen.getByRole("button", { name: "I don't have all documents" })).toBeEnabled();
  });
});
