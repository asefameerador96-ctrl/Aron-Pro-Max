// F-TSO-022: SR Device OTP panel (view only). The API shows the OTP value only to the TSO of the zone's scope.
import { DeviceOtpPageContent } from "@/components/admin/config/device-otp-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function SrDeviceOtpPanel({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <DeviceOtpPageContent searchParams={searchParams} action="/device-otp" roles={["TSO", "SUPPORT", "ADMIN", "SUPERADMIN"]} canIssue={() => false} />;
}
