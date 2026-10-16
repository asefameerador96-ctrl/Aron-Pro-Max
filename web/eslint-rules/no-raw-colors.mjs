// Local ESLint rule: colours come only from the Calm Glass tokens (docs/design/web-glass.md s2.5). In the files it covers it flags
// (1) raw hex colours, (2) Tailwind's `slate-*` palette, (3) `bg-white` / `text-white`, (4) a `dark:` variant on a colour
// utility (colours flip by variable, never by variant). String literals and template text are checked wherever they appear,
// because class recipes live in constants as often as in `className`. Disable one line with
// `// eslint-disable-next-line local/no-raw-colors`.

const HEX = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3,4})(?![0-9a-z-])/i;
const SLATE = /(?:^|[\s"'`:[(])(?:[a-z-]+:)*(?:bg|text|border|ring|from|to|via|fill|stroke|outline|divide|placeholder|shadow|accent|caret|decoration)-slate-\d{2,3}\b/;
const WHITE = /(?:^|[\s"'`:[(])(?:[a-z-]+:)*(?:bg|text)-white\b/;
const DARK = /(?:^|[\s"'`])dark:(?:[a-z-]+:)*(?:bg|text|border|ring|from|to|via|fill|stroke|outline|divide|shadow)-/;

function offence(text) {
  if (HEX.test(text)) return "hex";
  if (SLATE.test(text)) return "slate";
  if (WHITE.test(text)) return "white";
  if (DARK.test(text)) return "dark";
  return null;
}

/** @type {import("eslint").Rule.RuleModule} */
export const noRawColors = {
  meta: {
    type: "problem",
    schema: [],
    messages: {
      hex: "Raw hex colour in {{text}}: use a token utility (text-primary, bg-raised, bg-accent-container...) or a CSS variable from src/styles/tokens.css.",
      slate: "Tailwind slate-* in {{text}}: use the token utilities (text-secondary, border-divider, bg-raised...).",
      white: "bg-white / text-white in {{text}}: use bg-raised, bg-solid or text-on-accent.",
      dark: "dark: variant on a colour in {{text}}: token colours flip by variable, the dark variant is for images and the map only.",
    },
  },
  create(context) {
    const check = (node, text) => {
      const id = offence(text);
      if (id) context.report({ node, messageId: id, data: { text: JSON.stringify(text.length > 60 ? `${text.slice(0, 57)}...` : text) } });
    };
    return {
      Literal(node) {
        if (typeof node.value === "string") check(node, node.value);
      },
      TemplateElement(node) {
        check(node, node.value.cooked ?? node.value.raw);
      },
    };
  },
};

/** @type {import("eslint").ESLint.Plugin} */
const plugin = { rules: { "no-raw-colors": noRawColors } };
export default plugin;
