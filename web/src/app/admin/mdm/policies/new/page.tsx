import { PolicyEditor } from "@/components/admin/mdm/policy-editor";
import { PolicyPageFrame } from "@/components/admin/mdm/policies-view";
import { mdmContext } from "@/components/admin/mdm/shared";
import { Forbidden } from "@/components/forbidden";
import { t } from "@/lib/i18n";

export default async function MdmPolicyNewPage() {
  const { ctx, forbidden } = await mdmContext();
  if (forbidden) return forbidden;
  if (ctx.level !== "w") return <Forbidden locale={ctx.locale} />;
  return (
    <PolicyPageFrame locale={ctx.locale} title={t(ctx.locale, "mdm.policy.new")}>
      <PolicyEditor initial={null} canWrite />
    </PolicyPageFrame>
  );
}
