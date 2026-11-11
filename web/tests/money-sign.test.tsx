// D-WEB-FIX-01: the taka sign of the Money component (kit/hero-card): "Tk" from the catalogue on English pages, the Bengali sign in a
// lang=bn span on Bangla pages, none when `plain`. The shipped overview hero passes `plain`, so this proves the branch for a later caller.
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { Money } from "@/components/kit/hero-card";

describe("Money sign", () => {
  it("English prints Tk and pulls no Bengali-face span", () => {
    const html = renderToStaticMarkup(<Money amount="12.50" locale="en" />);
    expect(html).toContain("Tk");
    expect(html).not.toContain("৳");
    expect(html).not.toContain('lang="bn"');
  });
  it("Bangla (and the default) keeps the sign in a lang=bn span", () => {
    for (const html of [renderToStaticMarkup(<Money amount="১২.৫০" locale="bn" />), renderToStaticMarkup(<Money amount="১২.৫০" />)]) {
      expect(html).toContain('<span class="taka" lang="bn">৳</span>');
      expect(html).not.toContain("Tk");
    }
  });
  it("plain prints the figure only, in either language", () => {
    for (const locale of ["en", "bn"] as const) {
      const html = renderToStaticMarkup(<Money amount="5.43 Lakh" locale={locale} plain />);
      expect(html).not.toContain("Tk");
      expect(html).not.toContain("৳");
    }
  });
});
