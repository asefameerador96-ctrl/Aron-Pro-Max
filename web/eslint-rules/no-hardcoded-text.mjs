// Local ESLint rule: user-visible text must come from the i18n catalogue (docs/26 s5: Bangla first, no hard-coded text).
// Flags (1) JSX text with letters, (2) string literals rendered directly as JSX children, (3) string literals in
// user-visible attributes. Brand names are allowed. Disable a single line with `// eslint-disable-next-line local/no-hardcoded-text`.

const VISIBLE_ATTRS = new Set(["title", "placeholder", "alt", "aria-label", "aria-description", "aria-placeholder", "label"]);
const ALLOWED_WORDS = /\b(Aron|AKTCL|TOTP|OTP|IP|ID|API|PDF|CSV|XLSX)\b/g;

function hasVisibleLetters(text) {
  const stripped = text.replace(ALLOWED_WORDS, "");
  return /\p{L}/u.test(stripped);
}

/** Visible string pieces an expression can render: literals, template text, either branch of ?: / && / ||, both sides of +. */
function literals(e) {
  switch (e.type) {
    case "Literal":
      return typeof e.value === "string" ? [e] : [];
    case "TemplateLiteral":
      return e.quasis.filter((q) => hasVisibleLetters(q.value.cooked ?? "")).map((q) => ({ ...q, value: q.value.cooked }));
    case "ConditionalExpression":
      return [...literals(e.consequent), ...literals(e.alternate)];
    case "LogicalExpression":
    case "BinaryExpression":
      return [...literals(e.left), ...literals(e.right)];
    default:
      return [];
  }
}

/** @type {import("eslint").Rule.RuleModule} */
export const noHardcodedText = {
  meta: {
    type: "problem",
    schema: [],
    messages: {
      text: "Hard-coded user-visible text {{text}}. Add a key to the bn and en catalogues and use t().",
    },
  },
  create(context) {
    const report = (node, raw) =>
      context.report({ node, messageId: "text", data: { text: JSON.stringify(String(raw).trim().slice(0, 40)) } });
    const check = (expr) => {
      for (const l of literals(expr)) if (hasVisibleLetters(String(l.value))) report(l, l.value);
    };
    return {
      JSXText(node) {
        if (hasVisibleLetters(node.value)) report(node, node.value);
      },
      JSXExpressionContainer(node) {
        if (node.parent.type === "JSXAttribute") return;
        check(node.expression);
      },
      JSXAttribute(node) {
        if (node.name.type !== "JSXIdentifier" || !VISIBLE_ATTRS.has(node.name.name)) return;
        const v = node.value;
        if (!v) return;
        if (v.type === "Literal" && typeof v.value === "string" && hasVisibleLetters(v.value)) report(v, v.value);
        if (v.type === "JSXExpressionContainer") check(v.expression);
      },
    };
  },
};

const plugin = { rules: { "no-hardcoded-text": noHardcodedText } };
export default plugin;
