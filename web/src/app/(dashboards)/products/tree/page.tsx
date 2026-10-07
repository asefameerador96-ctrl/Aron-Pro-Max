import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listProductNodes, listSkus } from "@/lib/dash/server";
import { LEVELS, buildTree, countLeaves, type Level, type Node, type TreeNode } from "@/lib/dash/products";
import { problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";
import { n } from "@/components/dash/tiles";

function Branch({ node, locale }: { node: TreeNode; locale: Locale }) {
  const label = (
    <span data-level={node.level} data-status={node.status}>
      {node.label}
      {node.level !== "root" && node.level !== "sku" ? <span className="ml-2 text-xs text-slate-500">{t(locale, `filter.product_type.${node.level}` as MessageKey)}</span> : null}
      {node.status === "inactive" ? <span className="ml-2 text-xs text-amber-800">{t(locale, "entity.status.inactive")}</span> : null}
    </span>
  );
  if (node.children.length === 0) return <li className="py-0.5 pl-5 text-sm">{label}</li>;
  return (
    <li>
      <details open={node.level === "root"} className="py-0.5">
        <summary className="cursor-pointer text-sm font-medium">{label}</summary>
        <ul className="ml-4 border-l border-slate-200 pl-2">
          {node.children.map((c) => (
            <Branch key={c.key} node={c} locale={locale} />
          ))}
        </ul>
      </details>
    </li>
  );
}

// Browse Product Tree (F-WEB-009): All Products expands from Category down to SKU, read-only.
export default async function ProductTreePage() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const [pages, skus] = await Promise.all([Promise.all(LEVELS.map((l) => listProductNodes(session.at, l))), listSkus(session.at)]);
  const failed = [...pages, skus].find((p) => !p.ok);
  if (failed && !failed.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
        {problemMessage(locale, failed.problem.code)}
      </p>
    );
  const all = Object.fromEntries(LEVELS.map((l, i) => [l, pages[i]!.ok ? (pages[i] as { ok: true; data: { items: Node[] } }).data.items : []])) as Record<Level, Node[]>;
  const tree = buildTree(all, skus.ok ? skus.data.items : [], t(locale, "products.all"));
  return (
    <div className="space-y-4" data-testid="product-tree">
      <h1 className="text-2xl font-bold">{t(locale, "menu.products.tree")}</h1>
      <p className="text-sm text-slate-600" data-testid="tree-count">
        {t(locale, "products.sku_count", { n: n(locale, countLeaves(tree)) })}
      </p>
      <ul className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
        <Branch node={tree} locale={locale} />
      </ul>
    </div>
  );
}
