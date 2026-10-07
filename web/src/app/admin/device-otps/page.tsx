// F-ADM-022: device OTP administration (re-issue, search, OTP log).
import { DeviceOtpPageContent } from "@/components/admin/config/device-otp-page";
import type { SearchParams } from "@/components/admin/kit/page";
import { canOp } from "@/lib/admin/access";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

export default function DeviceOtpAdminPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <DeviceOtpPageContent searchParams={searchParams} action="/admin/device-otps" roles={ADMIN_PORTAL_ROLES} canIssue={(r) => canOp("device-otp.issue", r)} />;
}
