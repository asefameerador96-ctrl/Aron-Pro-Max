// F-ADM-078 replace-device wizard (maker only): the old phone's pending rows, upload first or revoke now, OTP for the new phone.
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { Card, PageHeading, Stat } from "@/components/admin/kit/page";
import type { Device } from "@/lib/admin/types";
import { formatNumber, t, type Locale } from "@/lib/i18n";

export function DeviceReplaceView({ locale, device }: { locale: Locale; device: Device }) {
  const fields: OpFieldDef[] = [
    {
      name: "mode",
      label: t(locale, "dev.replace.mode"),
      kind: "enum",
      required: true,
      options: [
        { value: "upload_first", label: t(locale, "dev.replace.upload_first") },
        { value: "revoke_now", label: t(locale, "dev.replace.revoke_now") },
      ],
    },
    { name: "new_device_user_id", label: t(locale, "dev.replace.user"), kind: "int", nullable: true, hint: t(locale, "dev.replace.user.hint"), initial: "" },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "dev.replace.title")} intro={t(locale, "dev.replace.intro")} />
      <Card>
        <Stat label={t(locale, "dev.replace.pending")} value={device.pending_rows_reported == null ? "—" : formatNumber(locale, device.pending_rows_reported)} testId="pending-rows" />
        <p className="text-sm text-slate-600">{device.bound_users.map((u) => u.username).join(", ")}</p>
      </Card>
      <OpForm op="device.replace" params={{ device_id: String(device.device_id) }} fields={fields} submitLabel={t(locale, "dev.replace.submit")} successKey="dev.replace.done" resultField="otp.otp" resetOnSuccess={false} testId="replace-form" />
    </div>
  );
}
