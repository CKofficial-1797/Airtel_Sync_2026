import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ExtractionUnavailableError, NetworkError } from "../../api/client";
import type { DocType, DocumentView, KycView } from "../../api/types";
import i18n from "../../i18n";
import unreadable from "../../test/fixtures/doc-unreadable.json";
import wrongType from "../../test/fixtures/doc-wrong-type.json";
import k03 from "../../test/fixtures/kyc-k03.json";
import { UploadSlot } from "./UploadSlot";

const k03Docs = (k03 as unknown as KycView).documents;
const docOf = (type: DocType) => k03Docs.find((d) => d.docType === type)!;
const png = (size = 10) => new File([new Uint8Array(size)], "photo.png", { type: "image/png" });

function setup(doc?: DocumentView, onUpload = vi.fn(async () => undefined)) {
  render(
    <ul>
      <UploadSlot docType="GST_CERTIFICATE" doc={doc} onUpload={onUpload} />
    </ul>,
  );
  return onUpload;
}

const fileInput = () => screen.getByTestId("file-GST_CERTIFICATE");

describe("UploadSlot", () => {
  afterEach(() => void i18n.changeLanguage("en"));

  it("rejects a non-image without uploading it", async () => {
    const onUpload = setup();
    const user = userEvent.setup({ applyAccept: false });
    await user.upload(fileInput(), new File(["%PDF"], "gst.pdf", { type: "application/pdf" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("PNG or JPEG");
    expect(onUpload).not.toHaveBeenCalled();
  });

  it("rejects a file over 5 MB without uploading it", async () => {
    const onUpload = setup();
    await userEvent.setup().upload(fileInput(), png(5 * 1024 * 1024 + 1));

    expect(await screen.findByRole("alert")).toHaveTextContent("5 MB");
    expect(onUpload).not.toHaveBeenCalled();
  });

  it("uploads an accepted file for its slot", async () => {
    const onUpload = setup();
    const file = png();
    await userEvent.setup().upload(fileInput(), file);

    await waitFor(() => expect(onUpload).toHaveBeenCalledWith("GST_CERTIFICATE", file));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the masked summary when a document was read", () => {
    setup(docOf("GST_CERTIFICATE"));

    expect(screen.getByText(/^Read$/)).toBeInTheDocument();
    expect(screen.getByTestId("summary-GST_CERTIFICATE")).toHaveTextContent("GSTIN 27XUI*****1ZP · Ganesh Woodcraft");
    expect(screen.getByText("Retake photo")).toBeInTheDocument();
  });

  it("shows the backend's fix hint and no read details for UNREADABLE", () => {
    setup(unreadable as unknown as DocumentView);

    expect(screen.getByText(/^Unreadable$/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Photo is blurry. Retake it in good light");
    expect(screen.queryByTestId("summary-GST_CERTIFICATE")).not.toBeInTheDocument();
    expect(screen.getByText("Retake photo")).toBeInTheDocument();
  });

  it("shows the Hindi fix hint when the UI is in Hindi", async () => {
    await i18n.changeLanguage("hi");
    setup(unreadable as unknown as DocumentView);
    expect(screen.getByRole("alert")).toHaveTextContent((unreadable as DocumentView & { fixHintHi: string }).fixHintHi);
  });

  it("shows the hint for WRONG_DOC_TYPE", () => {
    setup(wrongType as unknown as DocumentView);
    expect(screen.getByText(/^Wrong document$/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("This looks like the Udyam certificate");
  });

  it("flags a blank Udyam number instead of hiding it", () => {
    const doc = { ...docOf("UDYAM_CERTIFICATE"), fields: { udyamNumber: null, enterpriseName: "Vindhya Tours & Travels" } };
    render(
      <ul>
        <UploadSlot docType="UDYAM_CERTIFICATE" doc={doc} onUpload={vi.fn()} />
      </ul>,
    );
    expect(screen.getByTestId("summary-UDYAM_CERTIFICATE")).toHaveTextContent("Udyam number not found on the document · Vindhya");
  });

  it("keeps the saved document on a network error and retries the same file", async () => {
    const onUpload = vi.fn<(d: DocType, f: File) => Promise<void>>().mockRejectedValueOnce(new NetworkError()).mockResolvedValueOnce(undefined);
    setup(docOf("GST_CERTIFICATE"), onUpload as never);
    const user = userEvent.setup();
    const file = png();
    await user.upload(fileInput(), file);

    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach the server");
    expect(screen.getByTestId("summary-GST_CERTIFICATE")).toHaveTextContent("27XUI*****1ZP");

    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(onUpload).toHaveBeenCalledTimes(2));
    expect(onUpload.mock.calls[1]?.[1]).toBe(file);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("explains a busy reader and offers a retry", async () => {
    setup(undefined, vi.fn(async () => {
      throw new ExtractionUnavailableError("busy");
    }));
    await userEvent.setup().upload(fileInput(), png());

    expect(await screen.findByRole("alert")).toHaveTextContent("busy right now");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});
