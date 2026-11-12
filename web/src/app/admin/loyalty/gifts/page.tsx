import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { buttonClass } from "@/components/kit/button";
import { StatusChip } from "@/components/kit/chip";
import { LoyaltyGiftForm } from "@/components/admin/loyalty-gift-form";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { PageHeading, one, type SearchParams } from "@/components/admin/kit/page";
import { loadGrants } from "@/lib/admin/modules";
import { canReadLoyalty, canWriteGifts, giftFormFrom, ID_RE, type Gift } from "@/lib/admin/loyalty";
import { loadGifts, loadProgrammes } from "@/lib/admin/loyalty-load";
import { nameOf, pointsText } from "@/lib/admin/loyalty-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Gift catalogue (createGift / updateGift). Readable with the loyalty read grant; only a Super Admin changes it (Q3 default).
export default async function LoyaltyGiftsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canReadLoyalty(session.user.role, grants)) return <Forbidden locale={locale} />;
  const canWrite = canWriteGifts(session.user.role);
  const [gifts, programmes] = await Promise.all([loadGifts(session.at), loadProgrammes(session.at)]);
  const progs = programmes.ok ? programmes.data : [];
  const choices = progs.map((p) => ({ id: p.programme_id, label: `${p.code} · ${nameOf(locale, p)}`, tiers: p.tiers ?? [] }));
  const programmeLabel = (id: number): string => choices.find((c) => c.id === id)?.label ?? `#${id}`;
  const editId = ID_RE.test(one(sp.edit) ?? "") ? Number(one(sp.edit)) : null;
  const editing = editId !== null && gifts.ok ? gifts.data.find((g) => g.gift_id === editId) : undefined;
  const showNew = canWrite && one(sp.new) === "1";
  const columns: Column<Gift>[] = [
    { key: "code", header: t(locale, "loy.gifts.col.code"), render: (g) => g.code },
    { key: "name", header: t(locale, "loy.gifts.col.name"), render: (g) => (locale === "bn" && g.name_bn ? g.name_bn : g.name_en) },
    { key: "programme", header: t(locale, "loy.gifts.col.programme"), render: (g) => programmeLabel(g.programme_id) },
    { key: "points", header: t(locale, "loy.gifts.col.points"), align: "right", render: (g) => (g.points_cost === null ? t(locale, "loy.gifts.points_none") : pointsText(locale, g.points_cost)) },
    { key: "tiers", header: t(locale, "loy.gifts.col.tiers"), render: (g) => (g.tier_codes ?? []).join(", ") || "—" },
    { key: "status", header: t(locale, "loy.gifts.col.status"), render: (g) => <StatusChip tone={g.status === "active" ? "success" : "quiet"} testId={`gift-status-${g.code}`}>{t(locale, `loy.gifts.status.${g.status}` as "loy.gifts.status.active")}</StatusChip> },
    ...(canWrite ? [{ key: "edit", header: "", render: (g: Gift) => <Link href={`/admin/loyalty/gifts?edit=${g.gift_id}`} className="text-body-strong text-accent hover:underline" data-testid={`gift-edit-${g.code}`}>{t(locale, "loy.gifts.edit")}</Link> }] : []),
  ];
  return (
    <div className="space-y-4" data-testid="loyalty-gifts-page">
      <PageHeading
        title={t(locale, "loy.gifts.title")}
        intro={t(locale, "loy.gifts.intro")}
        actions={<><Link href="/admin/loyalty" className="text-body-strong text-accent hover:underline">{t(locale, "loy.back")}</Link>{canWrite ? <Link href="/admin/loyalty/gifts?new=1" className={buttonClass("primary")} data-testid="gift-new">{t(locale, "loy.gifts.new")}</Link> : null}</>}
      />
      {one(sp.saved) === "1" ? <Banner tone="success" role="status" testId="gift-saved">{t(locale, "loy.gifts.saved")}</Banner> : null}
      {!canWrite ? <p className="text-caption text-secondary" data-testid="gifts-super-only">{t(locale, "loy.gifts.super_only")}</p> : null}
      {showNew ? <LoyaltyGiftForm mode="create" programmes={choices} cancelHref="/admin/loyalty/gifts" /> : null}
      {canWrite && editing ? <LoyaltyGiftForm key={editing.gift_id} mode="edit" giftId={editing.gift_id} version={editing.version} initial={giftFormFrom(editing)} original={editing} programmes={choices} cancelHref="/admin/loyalty/gifts" /> : null}
      {!gifts.ok ? (
        <Banner tone={gifts.offline ? "offline" : "danger"} role="alert" testId="gifts-error">{t(locale, gifts.offline ? "loy.offline" : "loy.gifts.failed")}</Banner>
      ) : (
        <DataTable columns={columns} rows={gifts.data} rowKey={(g) => String(g.gift_id)} empty={t(locale, "loy.gifts.empty")} caption={t(locale, "loy.gifts.title")} />
      )}
    </div>
  );
}
