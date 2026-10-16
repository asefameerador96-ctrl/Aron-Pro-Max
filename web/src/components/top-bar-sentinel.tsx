"use client";
// The top bar gets a divider and shadow once the page has scrolled 8 px (web-glass s6): an IntersectionObserver on a sentinel,
// not a scroll listener.
import { useEffect, useRef } from "react";

export function TopBarSentinel() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    const bar = document.querySelector<HTMLElement>('[data-testid="top-bar"]');
    if (!el || !bar || typeof IntersectionObserver === "undefined") return;
    // The sentinel sits in flow right under the sticky bar and the observer ignores occlusion, so the root's top edge is pulled
    // down to (bar height - 8 px): the sentinel then leaves the root exactly when the page has scrolled 8 px.
    const barH = bar.getBoundingClientRect().height || 56;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e?.isIntersecting) bar.removeAttribute("data-scrolled");
        else bar.setAttribute("data-scrolled", "");
      },
      { rootMargin: `-${Math.max(0, Math.round(barH) - 8)}px 0px 0px 0px` },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return <div ref={ref} aria-hidden="true" className="pointer-events-none h-px w-px" />;
}
