"use client";
// Re-reads the server page on a timer (a list that should show new rows within a minute). Pauses while the tab is hidden.
import { useRouter } from "next/navigation";
import { useEffect } from "react";

export function AutoRefresh({ seconds }: { seconds: number }) {
  const router = useRouter();
  useEffect(() => {
    const id = setInterval(() => {
      if (document.visibilityState === "visible") router.refresh();
    }, seconds * 1000);
    return () => clearInterval(id);
  }, [router, seconds]);
  return null;
}
