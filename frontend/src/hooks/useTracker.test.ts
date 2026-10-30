import { act, renderHook, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NetworkError } from "../api/client";
import * as trackApi from "../api/track";
import type { TrackerView } from "../api/types";
import active from "../test/fixtures/tracker-active.json";
import scheduled from "../test/fixtures/tracker-install-scheduled.json";
import verified from "../test/fixtures/tracker-kyc-verified.json";
import { AUTO_REFRESH_MS, useTracker } from "./useTracker";

const VERIFIED = verified as unknown as TrackerView;
const SCHEDULED = scheduled as unknown as TrackerView;
const ACTIVE = active as unknown as TrackerView;

function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("useTracker", () => {
  beforeEach(() => {
    setVisibility("visible");
    vi.spyOn(trackApi, "getTracker").mockResolvedValue(VERIFIED);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  async function loaded() {
    const hook = renderHook(() => useTracker("o1"));
    await waitFor(() => expect(hook.result.current.view).not.toBeNull());
    return hook;
  }

  /** With a faked setInterval waitFor can't poll, so settle the first load directly. */
  async function loadedWithFakeInterval() {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const hook = renderHook(() => useTracker("o1"));
    await act(async () => {});
    expect(hook.result.current.view).not.toBeNull();
    return hook;
  }

  it("opening the page is one counted view", async () => {
    await loaded();
    expect(trackApi.getTracker).toHaveBeenCalledTimes(1);
    expect(trackApi.getTracker).toHaveBeenCalledWith("o1", false);
  });

  it("stays one counted view under StrictMode's double effect", async () => {
    const hook = renderHook(() => useTracker("o1"), { wrapper: StrictMode });
    await waitFor(() => expect(hook.result.current.view).not.toBeNull());
    const counted = vi.mocked(trackApi.getTracker).mock.calls.filter(([, auto]) => auto === false);
    expect(counted).toHaveLength(1);
  });

  it("the Refresh button counts as a view", async () => {
    const { result } = await loaded();
    vi.mocked(trackApi.getTracker).mockResolvedValueOnce(SCHEDULED);
    await act(async () => {
      await result.current.refresh();
    });
    expect(trackApi.getTracker).toHaveBeenLastCalledWith("o1", false);
    expect(result.current.view?.currentStatus).toBe("INSTALL_SCHEDULED");
    expect(result.current.refreshing).toBe(false);
  });

  it("refreshes every 30 s while visible, without counting", async () => {
    const { result } = await loadedWithFakeInterval();
    vi.mocked(trackApi.getTracker).mockResolvedValue(SCHEDULED);

    await act(async () => {
      vi.advanceTimersByTime(AUTO_REFRESH_MS - 1);
    });
    expect(trackApi.getTracker).toHaveBeenCalledTimes(1);

    await act(async () => {
      vi.advanceTimersByTime(1);
    });
    expect(trackApi.getTracker).toHaveBeenCalledTimes(2);
    expect(trackApi.getTracker).toHaveBeenLastCalledWith("o1", true);
    expect(result.current.view?.currentStatus).toBe("INSTALL_SCHEDULED");
  });

  it("doesn't refresh while the tab is hidden, and catches up when it returns", async () => {
    await loadedWithFakeInterval();
    setVisibility("hidden");
    await act(async () => {
      vi.advanceTimersByTime(AUTO_REFRESH_MS * 3);
    });
    expect(trackApi.getTracker).toHaveBeenCalledTimes(1);

    setVisibility("visible");
    await act(async () => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
    expect(trackApi.getTracker).toHaveBeenCalledTimes(2);
    expect(trackApi.getTracker).toHaveBeenLastCalledWith("o1", true);
  });

  it("stops refreshing when the page closes", async () => {
    const { unmount } = await loadedWithFakeInterval();
    unmount();
    await act(async () => {
      vi.advanceTimersByTime(AUTO_REFRESH_MS * 2);
    });
    expect(trackApi.getTracker).toHaveBeenCalledTimes(1);
  });

  it("a slow answer can't undo what an action just returned", async () => {
    const { result } = await loaded();
    const slow = deferred<TrackerView>();
    vi.mocked(trackApi.getTracker).mockReturnValueOnce(slow.promise);

    let refreshing!: Promise<void>;
    act(() => {
      refreshing = result.current.refresh();
    });
    act(() => result.current.apply(ACTIVE));
    await act(async () => {
      slow.resolve(VERIFIED);
      await refreshing;
    });
    expect(result.current.view?.currentStatus).toBe("ACTIVE");
  });

  it("a failed refresh keeps the page and reports the problem; the next one clears it", async () => {
    const { result } = await loaded();
    vi.mocked(trackApi.getTracker).mockRejectedValueOnce(new NetworkError());
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.error).toBe("network");
    expect(result.current.view?.currentStatus).toBe("KYC_VERIFIED");

    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.error).toBeNull();
  });
});
