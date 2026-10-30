import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ConsentRequiredError, NetworkError } from "../api/client";
import * as api from "../api/kyc";
import type { DocumentView, KycView } from "../api/types";
import k03Json from "../test/fixtures/kyc-k03.json";
import { useKyc } from "./useKyc";

const k03 = k03Json as unknown as KycView;
const inProgress: KycView = { ...k03, kycStatus: "KYC_IN_PROGRESS", orderStatus: "KYC_IN_PROGRESS", documents: [], overall: null, checks: [] };
const doc = (type: string) => k03.documents.find((d) => d.docType === type) as DocumentView;
const png = new File([new Uint8Array(4)], "a.png", { type: "image/png" });

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("useKyc", () => {
  beforeEach(() => {
    vi.spyOn(api, "getKyc").mockResolvedValue(inProgress);
  });
  afterEach(() => vi.restoreAllMocks());

  async function loaded() {
    const hook = renderHook(() => useKyc("o1"));
    await waitFor(() => expect(hook.result.current.view).not.toBeNull());
    return hook;
  }

  it("keeps every document when uploads finish together", async () => {
    const { result } = await loaded();
    vi.spyOn(api, "uploadDocument").mockImplementation(async (_o, type) => doc(type));

    await act(async () => {
      await Promise.all([result.current.upload("GST_CERTIFICATE", png), result.current.upload("ADDRESS_PROOF", png)]);
    });

    expect(result.current.view?.documents.map((d) => d.docType).sort()).toEqual(["ADDRESS_PROOF", "GST_CERTIFICATE"]);
  });

  /** Regression: a slow GET started before an upload landed afterwards and wiped the slot. */
  it("ignores a GET that started before an upload finished", async () => {
    const { result } = await loaded();
    const slowGet = deferred<KycView>();
    vi.mocked(api.getKyc).mockReturnValueOnce(slowGet.promise);
    vi.spyOn(api, "uploadDocument").mockResolvedValue(doc("GST_CERTIFICATE"));

    let reload!: Promise<void>;
    act(() => {
      reload = result.current.reload();
    });
    await act(async () => {
      await result.current.upload("GST_CERTIFICATE", png);
    });
    await act(async () => {
      slowGet.resolve(inProgress); // the stale answer: no documents
      await reload;
    });

    expect(result.current.view?.documents.map((d) => d.docType)).toEqual(["GST_CERTIFICATE"]);
  });

  it("a GET started after the upload is applied", async () => {
    const { result } = await loaded();
    vi.spyOn(api, "uploadDocument").mockResolvedValue(doc("GST_CERTIFICATE"));
    await act(async () => {
      await result.current.upload("GST_CERTIFICATE", png);
    });

    vi.mocked(api.getKyc).mockResolvedValueOnce(k03);
    await act(async () => {
      await result.current.reload();
    });
    expect(result.current.view?.kycStatus).toBe("KYC_VERIFIED");
  });

  it("a lost consent reloads the state and still reports the failure to the slot", async () => {
    const { result } = await loaded();
    vi.spyOn(api, "uploadDocument").mockRejectedValue(new ConsentRequiredError("no consent"));
    const pending: KycView = { ...inProgress, kycStatus: "KYC_PENDING_CONSENT", consent: { ...inProgress.consent, active: false } };
    vi.mocked(api.getKyc).mockResolvedValueOnce(pending);

    let thrown: unknown;
    await act(async () => {
      thrown = await result.current.upload("GST_CERTIFICATE", png).catch((e: unknown) => e);
    });

    expect(thrown).toBeInstanceOf(ConsentRequiredError);
    expect(result.current.view?.consent.active).toBe(false);
  });

  it("a failed upload keeps the documents already saved", async () => {
    const { result } = await loaded();
    vi.spyOn(api, "uploadDocument").mockResolvedValueOnce(doc("GST_CERTIFICATE")).mockRejectedValueOnce(new NetworkError());
    await act(async () => {
      await result.current.upload("GST_CERTIFICATE", png);
    });
    await act(async () => {
      await result.current.upload("SIGNATORY_ID", png).catch(() => undefined);
    });
    expect(result.current.view?.documents.map((d) => d.docType)).toEqual(["GST_CERTIFICATE"]);
  });

  it("a network error on submit is reported and keeps the view", async () => {
    const { result } = await loaded();
    vi.spyOn(api, "submitKyc").mockRejectedValue(new NetworkError());
    await act(async () => {
      await result.current.submit();
    });
    expect(result.current.submitError).toBe("network");
    expect(result.current.view?.kycStatus).toBe("KYC_IN_PROGRESS");
    expect(result.current.submitting).toBe(false);
  });

  it("reports an unknown order as notFound", async () => {
    const { ApiError } = await import("../api/client");
    vi.mocked(api.getKyc).mockRejectedValueOnce(new ApiError(404, "nope"));
    const { result } = renderHook(() => useKyc("missing"));
    await waitFor(() => expect(result.current.loadError).toBe("notFound"));
  });
});
