# Request: web-config -> backend-admin / contract: Device OTP panel columns

Rows: F-TSO-022, F-ADM-022. The manual's panel (delta-sr G-man-sr-18, delta-web G-man-web-11) has eight columns:
Sr No., Field Force ID, Field Force Name, Username, Zone ID, Zone, Create Time, OTP.

`DeviceOtp` (contract) carries `user_id`, `username`, `full_name`, `zone_id`, `created_at`, `otp`, but **no employee code
(Field Force ID) and no zone name/code**. Until it does, the web shows `user_id` as Field Force ID, `zone_id` as Zone ID and has
no Zone name column (`// REQUEST: web-config-device-otp-columns`).

Needed shape: add to `DeviceOtp`: `employee_code` (string, nullable) and `zone_name` (string, nullable; `zone_code` optional).
No other change; the web adds the two columns when they exist.
