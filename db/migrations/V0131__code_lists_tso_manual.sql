-- V0131 code lists of the TSO manual build (contract docs/requests/tso-manual-build-2026-10.md s2.2; D-TSO-MAN-19,
-- D-TSO-MAN-20, D-TSO-MAN-36; gap items TK-03, TK-21, FB-03; lane F2).
--
-- 1. Two new lists: ticket_issue_type (the 31 issue types of Annex A of the owner's October 2026 TSO manual, labels
--    verbatim, in Annex A order, sort 1..31) and ticket_resolution_code (the desk's resolution codes, seed resolved =
--    "Resolved"). The code_list key CHECK is replaced NOT VALID with both keys added (every existing row keeps a
--    listed key) and validated in V0132 (separate transaction, squawk rule).
-- 2. Issue type attrs (D-TSO-MAN-19): priority "p1" (screenshot 15 shows "Tickets · High" on every tile, and every
--    ticket in screenshots 11 to 14 is "P1 - Highest"), category "tickets", urgency "high", icon = (sort - 1) % 8 (index
--    into key, fingerprint, screen_off, sync, navigation, receipt, cart, battery: screenshot 15), hint_en = Annex A's
--    "use it when" text. The Astha, Discount, Campaign and Target Panel types are labels only (docs/27 unaffected).
-- 3. feedback_category gains feedback / "Feedback", sort 2 (D-TSO-MAN-36 supersedes D-197's suggestion-only list;
--    screenshot 43 offers Suggestion and Feedback).
-- label_bn stays NULL until AKTCL supplies the Bangla labels (V0027 rule; the apps fall back to label_en; owner
-- question 5 of the contract s7.1). Idempotent: ON CONFLICT DO NOTHING keeps any label an admin already set.

SET lock_timeout = '5s';

ALTER TABLE app.code_list DROP CONSTRAINT code_list_list_key_check;
ALTER TABLE app.code_list ADD CONSTRAINT code_list_list_key_check CHECK (list_key IN (
  'channel','sub_channel','geo_class','force_reason','edit_reason','void_reason','visit_outcome','skip_reason',
  'day_exception_reason','stock_variance_reason','task_type','leave_type','feedback_category','qc_fault_type',
  'payment_mode','outlet_close_reason','submit_void_reason','ticket_issue_type','ticket_resolution_code')) NOT VALID;

INSERT INTO app.code_list (list_key, description) VALUES
  ('ticket_issue_type', 'Support ticket issue types (Annex A of the TSO manual); attrs: priority (default p1..p4), category, urgency, icon index, hint_en.'),
  ('ticket_resolution_code', 'Resolution codes the support desk picks when it resolves a ticket.')
ON CONFLICT (list_key) DO NOTHING;

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs)
SELECT 'ticket_issue_type', v.code, v.label_en, NULL, v.sort,
       jsonb_build_object('priority', 'p1', 'category', 'tickets', 'urgency', 'high', 'icon', (v.sort - 1) % 8, 'hint_en', v.hint_en)
  FROM (VALUES
    (1, 'app_crash_order_submit', 'App crashes on order submit', 'The app closes or freezes when a memo/order is submitted.'),
    (2, 'app_version_update', 'Application Version Update', 'A new app version is needed, or the app asks for an update that does not install.'),
    (3, 'astha_gift_disbursement', 'Astha Gift Choice & Disbursement', 'Problems choosing or handing over Astha gifts.'),
    (4, 'astha_target', 'Astha Target', 'Astha outlet targets are missing or wrong.'),
    (5, 'auto_logout', 'Auto Logout', 'The app logs out by itself.'),
    (6, 'camera_black_screen', 'Camera Black Screen', 'The camera shows a black screen when taking an outlet or force-sale photo.'),
    (7, 'campaign', 'Campaign', 'A campaign or trade offer is missing or not applied correctly.'),
    (8, 'change_request', 'Change Request', 'Asking for a change to the app or a report.'),
    (9, 'check_in_out', 'Check in Check out issue', 'Problems with SR check-in at the start or check-out at the end of the day.'),
    (10, 'cluster_mapping', 'Cluster Mapping', 'An outlet or route is in the wrong cluster.'),
    (11, 'discount', 'Discount', 'A discount or cashback is wrong on the memo.'),
    (12, 'ff_geo_location_map', 'FF Geo Location Map', 'The field force map shows wrong or no locations.'),
    (13, 'final_submission', 'Final Submission', 'Final Submit fails, or a zone needs to be reopened.'),
    (14, 'gps_inaccuracy', 'GPS location inaccuracy', 'The phone location is wrong, causing wrong geo-fencing.'),
    (15, 'login_otp', 'Login OTP issue', 'The login OTP does not arrive or is not accepted.'),
    (16, 'memo_printing', 'Memo Printing issue', 'The memo does not print or prints wrongly.'),
    (17, 'new_application_needed', 'New Application Needed (Tso, Amo, SR)', 'A new user needs the app and an account.'),
    (18, 'others', 'Others', 'Nothing else fits.'),
    (19, 'outlet_related', 'Outlet Related Issue', 'Any problem with an outlet''s data or sales.'),
    (20, 'outlet_request', 'Outlet request (Close, New, & Info Update)', 'Request to open a new outlet, close one, or change its information.'),
    (21, 'outlet_request_approval', 'Outlet Request Approval', 'An outlet request is waiting for approval or was not applied.'),
    (22, 'password_reset', 'Password Reset', 'A user forgot the password.'),
    (23, 'printer_connection', 'Printer Connection issue', 'The Bluetooth printer does not connect.'),
    (24, 'reporting', 'Reporting', 'A report is wrong, missing or does not open.'),
    (25, 'sales_adjustment', 'Sales Adjustment', 'Sales figures need correction.'),
    (26, 'sales_order_failing', 'Sales order creation failing', 'A memo/order cannot be created.'),
    (27, 'sales_submit', 'Sales Submit (Bikroy Joma)', 'Bikroy Joma (end-of-day sales submit) fails.'),
    (28, 'sync_stuck', 'Sync stuck / not updating', 'Download or upload does not complete, or data does not refresh.'),
    (29, 'target_panel', 'Target Panel', 'Targets are missing or wrong in the target screens.'),
    (30, 'user_login', 'User Login issue', 'A user cannot log in (other than OTP).'),
    (31, 'web_entry', 'Web Entry', 'Problems with Web Entry in the portal.')
  ) AS v(sort, code, label_en, hint_en)
ON CONFLICT (list_key, code) DO NOTHING;

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('ticket_resolution_code', 'resolved', 'Resolved', NULL, 1, '{}'),
  ('feedback_category', 'feedback', 'Feedback', NULL, 2, '{}')
ON CONFLICT (list_key, code) DO NOTHING;

SELECT app.apply_db_role_grants();
