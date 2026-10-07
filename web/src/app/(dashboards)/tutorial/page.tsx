import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listTutorials } from "@/lib/dash/server";
import { problemMessage, t } from "@/lib/i18n";

// Tutorial (F-WEB-035): the manuals and videos the portal manages for the caller's role. The list is the server's, so a video added in
// the admin portal shows here on the next load with no deploy. Files open from their own links (online only).
export default async function TutorialPage() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const r = await listTutorials(session.at);
  const items = r.ok ? [...r.data.items].sort((a, b) => a.sort - b.sort) : [];
  const group = (kind: "manual" | "video") => items.filter((i) => i.kind === kind);
  return (
    <div className="space-y-4" data-testid="tutorial">
      <h1 className="text-2xl font-bold">{t(locale, "menu.tutorial")}</h1>
      {!r.ok ? (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
          {problemMessage(locale, r.problem.code)}
        </p>
      ) : (
        (["manual", "video"] as const).map((k) => (
          <section key={k} data-testid={`tutorial-${k}`}>
            <h2 className="mb-1 text-lg font-semibold">{t(locale, k === "manual" ? "tutorial.manuals" : "tutorial.videos")}</h2>
            {group(k).length === 0 ? (
              <p className="text-sm text-slate-600">{t(locale, "common.empty")}</p>
            ) : (
              <ul className="grid gap-2 md:grid-cols-2">
                {group(k).map((i) => (
                  <li key={i.tutorial_id} className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm" data-tutorial={i.tutorial_id}>
                    <a href={i.url} target="_blank" rel="noreferrer" className="font-semibold text-brand-700 underline">
                      {locale === "bn" ? (i.title_bn ?? i.title_en) : i.title_en}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </section>
        ))
      )}
    </div>
  );
}
