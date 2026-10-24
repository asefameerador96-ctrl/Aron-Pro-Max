// Dashboard freshness (D-NIGHT-SYNC-01, web part): refresh every 30 s while the tab is visible, on focus and on becoming visible,
// never in a hidden tab; the "updated hh:mm:ss" caption follows each read. No DOM here: the hooks and the two event targets are stubbed.
import type * as React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const refresh = vi.fn();
const cleanups: Array<() => void> = [];
let state: unknown = null;
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("react", async (orig) => ({
  ...(await orig<typeof React>()),
  useRef: (v: unknown) => ({ current: v }),
  useState: (v: unknown) => [state ?? v, (n: unknown) => { state = n; }],
  useEffect: (fn: () => void | (() => void)) => { const c = fn(); if (c) cleanups.push(c); },
}));

type Listener = () => void;
const targets = () => {
  const map = new Map<string, Listener[]>();
  return {
    addEventListener: (e: string, l: Listener) => map.set(e, [...(map.get(e) ?? []), l]),
    removeEventListener: (e: string, l: Listener) => map.set(e, (map.get(e) ?? []).filter((x) => x !== l)),
    emit: (e: string) => (map.get(e) ?? []).forEach((l) => l()),
    count: (e: string) => (map.get(e) ?? []).length,
    visibilityState: "visible",
  };
};
let doc: ReturnType<typeof targets>;
let win: ReturnType<typeof targets>;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-24T09:00:00Z")); // 15:00:00 Dhaka
  refresh.mockClear(); state = null; cleanups.length = 0;
  doc = targets(); win = targets();
  vi.stubGlobal("document", doc); vi.stubGlobal("window", win);
});
afterEach(() => { cleanups.forEach((c) => c()); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("AutoRefresh", () => {
  it("refreshes every 30 s while visible, not before", async () => {
    const { AutoRefresh } = await import("@/components/dash/table-actions");
    AutoRefresh({});
    vi.advanceTimersByTime(29_000);
    expect(refresh).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1_000);
    expect(refresh).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(30_000);
    expect(refresh).toHaveBeenCalledTimes(2);
  });
  it("does nothing in a hidden tab, and reads once when it becomes visible with data older than 30 s", async () => {
    const { AutoRefresh } = await import("@/components/dash/table-actions");
    AutoRefresh({});
    doc.visibilityState = "hidden";
    vi.advanceTimersByTime(5 * 60_000);
    expect(refresh).not.toHaveBeenCalled();
    doc.visibilityState = "visible";
    doc.emit("visibilitychange");
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it("refreshes on window focus once the last read is 5 s old, not on a focus storm", async () => {
    const { AutoRefresh } = await import("@/components/dash/table-actions");
    AutoRefresh({});
    win.emit("focus");
    expect(refresh).not.toHaveBeenCalled();
    vi.advanceTimersByTime(6_000);
    win.emit("focus");
    expect(refresh).toHaveBeenCalledTimes(1);
    win.emit("focus");
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it("removes its timer and listeners on unmount", async () => {
    const { AutoRefresh } = await import("@/components/dash/table-actions");
    AutoRefresh({});
    expect(win.count("focus")).toBe(1);
    cleanups.splice(0).forEach((c) => c());
    expect(win.count("focus")).toBe(0);
    expect(doc.count("visibilitychange")).toBe(0);
    vi.advanceTimersByTime(120_000);
    expect(refresh).not.toHaveBeenCalled();
  });
  it("the caption shows the Dhaka time of the last read", async () => {
    const { AutoRefresh } = await import("@/components/dash/table-actions");
    AutoRefresh({ label: "Updated {time}" }); // first render: state still null
    expect(state).toBe(Date.parse("2026-10-24T09:00:00Z"));
    const el = AutoRefresh({ label: "Updated {time}" }) as { props: { children: unknown[] } };
    expect(el.props.children.join("")).toContain("Updated 15:00:00");
  });
});
