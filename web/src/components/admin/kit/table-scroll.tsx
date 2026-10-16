"use client";
// The one scroll region of a data table (web-glass s8.1). It stamps `data-stuck` while rows have scrolled under the sticky
// header, which is what switches the header from the plain 86 percent fill to the blurred glass bar in tier A (glass.css
// `.table-scroll[data-stuck] thead th`); unstuck, nothing passes under the header, so it stays unblurred (no ghost text).
import { useState, type HTMLAttributes, type ReactNode } from "react";

export function TableScroll({ children, className = "", ...rest }: { children: ReactNode; className?: string } & HTMLAttributes<HTMLDivElement>) {
  const [stuck, setStuck] = useState(false);
  return (
    <div
      {...rest}
      className={`table-scroll ${className}`}
      data-stuck={stuck ? "" : undefined}
      onScroll={(e) => {
        const next = e.currentTarget.scrollTop > 0;
        if (next !== stuck) setStuck(next);
        rest.onScroll?.(e);
      }}
    >
      {children}
    </div>
  );
}
