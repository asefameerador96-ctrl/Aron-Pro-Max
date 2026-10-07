// F-ADM-027 app release management and F-ADM-049 Config page P12: register, publish, minimum and blocked versions, adoption.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { Adoption } from "@/lib/admin/adoption";
import type { AppRelease, ConfigKey, ReleasePolicyList, ResolvedConfigValue } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";

const ABIS = ["universal", "arm64-v8a", "armeabi-v7a"] as const;
const FLAVOURS = ["sr", "amo", "tso"] as const;
const MB = 1024 * 1024;

export interface ReleasesViewProps {
  locale: Locale;
  releases: AppRelease[];
  policy: ReleasePolicyList["items"];
  adoption: Adoption[];
  policyKeys: ConfigKey[];
  policyValues: Record<string, ResolvedConfigValue>;
  apkMaxMb: number;
  canWrite: boolean;
  canPublish: boolean;
}

export function ReleasesView({ locale, releases, policy, adoption, policyKeys, policyValues, apkMaxMb, canWrite, canPublish }: ReleasesViewProps) {
  const columns: Column<AppRelease>[] = [
    { key: "f", header: t(locale, "dev.col.flavour"), render: (r) => t(locale, `dev.flavour.${r.flavour}` as MessageKey) },
    { key: "v", header: t(locale, "rel.col.version"), render: (r) => r.version_name },
    { key: "c", header: t(locale, "rel.col.code"), render: (r) => formatNumber(locale, r.version_code, { useGrouping: false }) },
    { key: "a", header: t(locale, "rel.col.abi"), render: (r) => r.abi },
    { key: "s", header: t(locale, "rel.col.size"), render: (r) => `${formatNumber(locale, Math.round((r.size_bytes / MB) * 10) / 10)} MB` },
    { key: "h", header: t(locale, "rel.col.sha"), render: (r) => <code className="text-xs">{r.sha256.slice(0, 12)}…</code> },
    { key: "st", header: t(locale, "rel.col.status"), render: (r) => t(locale, `rel.status.${r.status}` as MessageKey) },
    { key: "ro", header: t(locale, "rel.col.rollout"), render: (r) => `${formatNumber(locale, r.rollout_pct)}%`, align: "right" },
    {
      key: "act",
      header: t(locale, "common.actions"),
      render: (r) => {
        const params = { release_id: String(r.release_id) };
        return (
          <div className="flex flex-wrap items-start gap-2" data-testid={`release-${r.release_id}`}>
            {canPublish && r.status === "draft" ? <OpInline op="release.publish" params={params} version={r.version} body={{ status: "published" }} label={t(locale, "rel.publish")} successKey="rel.done" testId={`publish-${r.release_id}`} /> : null}
            {canWrite && r.status === "published" ? <OpInline op="release.update" params={params} version={r.version} body={{ status: "blocked" }} label={t(locale, "rel.block")} danger successKey="rel.done" testId={`block-${r.release_id}`} /> : null}
            {canWrite && (r.status === "published" || r.status === "blocked") ? <OpInline op="release.update" params={params} version={r.version} body={{ status: "retired" }} label={t(locale, "rel.retire")} successKey="rel.done" testId={`retire-${r.release_id}`} /> : null}
          </div>
        );
      },
    },
  ];
  const fields: OpFieldDef[] = [
    { name: "flavour", label: t(locale, "rel.field.flavour"), kind: "enum", required: true, options: FLAVOURS.map((f) => ({ value: f, label: t(locale, `dev.flavour.${f}` as MessageKey) })) },
    { name: "version_name", label: t(locale, "rel.field.version_name"), kind: "text", required: true, pattern: "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$" },
    { name: "version_code", label: t(locale, "rel.field.version_code"), kind: "int", required: true },
    { name: "abi", label: t(locale, "rel.field.abi"), kind: "enum", required: true, options: ABIS.map((a) => ({ value: a, label: a })) },
    { name: "sha256", label: t(locale, "rel.field.sha256"), kind: "text", required: true, pattern: "^[0-9a-f]{64}$", maxLength: 64 },
    { name: "signing_cert_sha256", label: t(locale, "rel.field.signing"), kind: "text", required: true, pattern: "^[0-9a-f]{64}$", maxLength: 64 },
    { name: "size_bytes", label: t(locale, "rel.field.size"), kind: "int", required: true, max: apkMaxMb * MB, hint: t(locale, "rel.size_gate", { mb: apkMaxMb }) },
    { name: "download_url", label: t(locale, "rel.field.url"), kind: "text", required: true, pattern: "^https://\\S+$", maxLength: 1000 },
    { name: "notes_en", label: t(locale, "rel.field.notes_en"), kind: "textarea", nullable: true, maxLength: 2000 },
    { name: "notes_bn", label: t(locale, "rel.field.notes_bn"), kind: "textarea", nullable: true, maxLength: 2000 },
  ];
  const maxDevices = Math.max(1, ...adoption.map((a) => a.devices));
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "rel.title")} intro={t(locale, "rel.intro")} />
      <DataTable columns={columns} rows={releases} rowKey={(r) => String(r.release_id)} empty={t(locale, "common.empty")} caption={t(locale, "rel.title")} />
      {canWrite && !canPublish ? <p className="text-xs text-slate-600">{t(locale, "rel.publish_note")}</p> : null}
      <Card title={t(locale, "rel.policy")} testId="release-policy">
        <ul className="space-y-1 text-sm">
          {policy.map((p) => (
            <li key={p.flavour}>
              <strong>{t(locale, `dev.flavour.${p.flavour}` as MessageKey)}</strong>: {t(locale, "rel.policy.latest")} {p.latest_version_code ?? "—"} · {t(locale, "rel.policy.min")} {p.min_version_code} · {t(locale, "rel.policy.blocked")} {p.blocked_version_codes.join(", ") || "—"} · {t(locale, "rel.policy.below")} {formatNumber(locale, p.devices_below_min ?? 0)}
            </li>
          ))}
        </ul>
        {canWrite
          ? policyKeys.map((k) => (
              <ConfigSetForm key={k.key} keyName={k.key} valueType={k.value_type} bounds={k.bounds} scopeLevels={k.scope_levels} scope={{ type: "global", id: 0 }} current={policyValues[k.key]?.value ?? k.default_value} label={k.key} testId={`policy-${k.key}`} />
            ))
          : null}
      </Card>
      <Card title={t(locale, "rel.adoption")} testId="adoption">
        <ul className="space-y-1">
          {adoption.map((a) => (
            <li key={`${a.flavour}-${a.version}`} className="flex items-center gap-2 text-sm">
              <span className="w-28 shrink-0">{t(locale, `dev.flavour.${a.flavour as "sr"}` as MessageKey)} {a.version}</span>
              <span className="h-3 rounded bg-brand-600" style={{ width: `${Math.max(2, Math.round((a.devices / maxDevices) * 100))}%` }} role="img" aria-label={t(locale, "rel.adoption.devices", { n: formatNumber(locale, a.devices) })} />
              <span className="text-slate-600">{formatNumber(locale, a.devices)}</span>
            </li>
          ))}
        </ul>
      </Card>
      {canWrite ? (
        <Card title={t(locale, "rel.new")}>
          <OpForm op="release.create" fields={fields} noReason submitLabel={t(locale, "rel.new")} successKey="rel.created" testId="release-form" />
        </Card>
      ) : null}
    </div>
  );
}
