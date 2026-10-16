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
    const io = new IntersectionObserver(([e]) => {
      if (e?.isIntersecting) bar.removeAttribute("data-scrolled");
      else bar.setAttribute("data-scrolled", "");
    });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return <div ref={ref} aria-hidden="true" className="pointer-events-none h-px w-px" />;
}
