// Contract types the configuration console works with (aliases of the generated schemas; a rename breaks `tsc`).
import type { components } from "@/contract/types";

type S = components["schemas"];
export type ConfigKey = S["ConfigKey"];
export type ConfigBounds = S["ConfigBounds"];
export type ConfigScopeType = S["ConfigScopeType"];
export type ConfigChange = S["ConfigChange"];
export type ConfigChangePage = S["ConfigChangePage"];
export type ConfigVersion = S["ConfigVersion"];
export type ConfigVersionPage = S["ConfigVersionPage"];
export type ConfigVersionDetail = S["ConfigVersionDetail"];
export type ConfigReach = S["ConfigReach"];
export type ConfigPendingDevicePage = S["ConfigPendingDevicePage"];
export type ConfigValue = S["ConfigValue"];
export type ConfigValuePage = S["ConfigValuePage"];
export type ResolvedConfigValue = S["ResolvedConfigValue"];
export type Holiday = S["Holiday"];
export type HolidayList = S["HolidayList"];
export type DeviceOtp = S["DeviceOtp"];
export type DeviceOtpPage = S["DeviceOtpPage"];
export type AuditEntry = S["AuditEntry"];
export type AuditPage = S["AuditPage"];
