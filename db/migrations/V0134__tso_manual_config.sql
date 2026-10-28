-- V0134 settings, permission and event catalogue of the TSO manual build (contract docs/requests/tso-manual-build-2026-10.md
-- s2.4, D-TSO-MAN-05..08, 10, 17, 22, 23, 26, 29..31, 34, 35, 38; gap items TK-21, T-05, PER-07, SP-03, LV-21, LV-22,
-- FS-11, FS-13, DP-01..05; lane F2).
--
-- 1. Fifteen config keys (columns as V0086; risk class 1 unless the contract names another). Editor permission: as the
--    contract's brackets; a row without a bracket takes its area's (kpi, tso, leave: cfg.edit.field) and
--    cfg.support.ticket_max_photos and cfg.feedback.max_images take cfg.edit.ops like the other photo and upload limits
--    (cfg.support.max_upload_mb). List keys: the config editor checks max_items only; the item rules (subset of SR, AMO,
--    TSO; two ascending integers 1..99) are written in bounds_rule and enforced by the reading module.
-- 2. Default changes (D-TSO-MAN-29, D-TSO-MAN-31; owner question 1 of the contract s7.1 keeps quarantine as the
--    default): cfg.day.late_sync_after_final_policy accept_and_flag -> quarantine; cfg.day.reopen_roles ["ADMIN"] ->
--    ["ADMIN","SUPERADMIN"]. No backend code read either key before this build.
-- 3. Permission support.ticket.manage (the support desk, D-TSO-MAN-22) for ADMIN and SUPERADMIN; SUPPORT can be granted
--    it on the permission page without code (owner question 4).
-- 4. Six domain-event types v1 (D-TSO-MAN-38), status planned until their producers (lanes B1, B3) ship; payloads carry
--    ids, codes and counts only (V0017 rule), never ticket text, names or reasons. Required keys are not enforced yet
--    (V0018 rule: a db migration turns enforce_required on after the producer sends them).

SET lock_timeout = '5s';

-- ---------- 1. config keys ----------
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.kpi.tilldate_basis.tso', 'kpi', 'S', 'enum', '"calendar_days"'::jsonb, '{"enum": ["calendar_days", "working_days"]}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'Till-date target basis on the TSO app (Target Status, dashboard): calendar_days = day of month / days in month (the screenshots), working_days = working days passed / working days of the month (D-TSO-MAN-05).'),
  ('cfg.kpi.tilldate_basis.amo_team', 'kpi', 'S', 'enum', '"calendar_days"'::jsonb, '{"enum": ["calendar_days", "working_days"]}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'Till-date target basis of the AMO Team Performance figures: calendar_days or working_days (D-TSO-MAN-05).'),
  ('cfg.kpi.brand_cpr_denominator.tso', 'kpi', 'S', 'enum', '"successful_calls"'::jsonb, '{"enum": ["successful_calls", "target_outlets"]}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'Denominator of the Brand CPR line on the TSO dashboard: successful_calls (the screenshot) or target_outlets (the manual text); the API sends both (D-TSO-MAN-06).'),
  ('cfg.kpi.bar_bands', 'kpi', 'S', 'list', '[40, 80]'::jsonb, '{"max_items": 2}'::jsonb, 'exactly 2 integers 1..99, ascending', ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Colour bands of the progress bars on the printed whole percent: red below the first, amber below the second, green from it (D-TSO-MAN-10, Target Status).'),
  ('cfg.support.ticket_requester_roles', 'support', 'S', 'list', '["TSO"]'::jsonb, '{"max_items": 3}'::jsonb, 'subset of SR, AMO, TSO', ARRAY['global']::text[], 2, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.security',
   'Roles that may raise support tickets from the phone; a ticket from another role is refused scope_out_of_reach (D-TSO-MAN-17).'),
  ('cfg.support.ticket_max_photos', 'support', 'S', 'int', '3'::jsonb, '{"min": 0, "max": 3}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.ops',
   'Most photos attached to one support ticket and to one ticket comment (D-TSO-MAN-23).'),
  ('cfg.notify.ticket_push_enabled', 'notify', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.ops',
   'Send a push nudge to the reporter''s phone when the support desk updates a ticket (the inbox row is written either way).'),
  ('cfg.notify.inbox_retention_days', 'notify', 'S', 'int', '90'::jsonb, '{"min": 30, "max": 365}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.ops',
   'Days a notification stays in the bell inbox (app.user_notification) before the prune job deletes it (D-TSO-MAN-26).'),
  ('cfg.day.auto_final_submit_enabled', 'day', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global', 'wing']::text[], 3, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'Final-submit every service zone that has no Final Submit at 00:00 Asia/Dhaka (AutoFinalSubmitJob, D-TSO-MAN-30); the zone-day then locks like a manual submit.'),
  ('cfg.tso.team_location_max_age_min', 'tso', 'S', 'int', '120'::jsonb, '{"min": 15, "max": 720}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Minutes after which a team member''s last synced position on My Team is shown greyed as stale (D-170, D-TSO-MAN-34).'),
  ('cfg.tso.plan_max_days_ahead', 'tso', 'S', 'int', '60'::jsonb, '{"min": 1, "max": 90}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Furthest date ahead a TSO may set a visit plan for, in days after today (Set Plan, D-TSO-MAN-35).'),
  ('cfg.leave.allow_past_days', 'leave', 'S', 'int', '31'::jsonb, '{"min": 0, "max": 90}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Most days in the past a leave may start (D-TSO-MAN-33); checked on the phone, on sync and on the web.'),
  ('cfg.leave.allow_ahead_days', 'leave', 'S', 'int', '365'::jsonb, '{"min": 1, "max": 365}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Most days ahead a leave may start (D-TSO-MAN-33).'),
  ('cfg.leave.block_overlap', 'leave', 'S', 'bool', 'false'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'Refuse a leave application that overlaps a pending or approved one of the same user (D-TSO-MAN-33: off).'),
  ('cfg.feedback.max_images', 'feedback', 'S', 'int', '1'::jsonb, '{"min": 0, "max": 3}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.ops',
   'Most gallery images attached to one feedback (screenshot 43: one, D-TSO-MAN-36).');

-- ---------- 2. default changes ----------
UPDATE app.cfg_key SET default_value = '"quarantine"'::jsonb, updated_at = now()
 WHERE key = 'cfg.day.late_sync_after_final_policy';
UPDATE app.cfg_key SET default_value = '["ADMIN", "SUPERADMIN"]'::jsonb, updated_at = now()
 WHERE key = 'cfg.day.reopen_roles';

-- ---------- 3. support desk permission ----------
INSERT INTO app.role_grant_map (role, permission) VALUES
  ('ADMIN', 'support.ticket.manage'),
  ('SUPERADMIN', 'support.ticket.manage')
ON CONFLICT (role, permission) DO NOTHING;

-- ---------- 4. domain events ----------
INSERT INTO app.domain_event_type
  (event_type, payload_version, aggregate_type, aggregate_id_is, producer, description, payload_schema, introduced_in) VALUES
('support_ticket.created', 1, 'support_ticket', 'support_ticket.client_uuid', 'backend:masterdata',
 'A support ticket was accepted from the phone (record support_ticket) with its INC number and default priority. Title and description are not in the payload.',
 '{"type":"object","required":["ticket_uuid","ticket_no","issue_type_code","priority","requester_user_id","business_date"],"properties":{
   "ticket_uuid":{"type":"string","format":"uuid","description":"support_ticket.client_uuid"},
   "ticket_no":{"type":"string","description":"INC number"},
   "issue_type_code":{"type":"string","description":"code list ticket_issue_type"},
   "priority":{"type":"string","enum":["p1","p2","p3","p4"]},
   "requester_user_id":{"type":"integer"},
   "requester_role":{"type":"string"},
   "business_date":{"type":"string","format":"date"},
   "source":{"type":"string","enum":["quick","manual"]},
   "has_affected_user":{"type":"boolean","description":"true when the ticket names another user (affected_username)"}}}', 'V0134'),
('support_ticket.status_changed', 1, 'support_ticket', 'support_ticket.client_uuid', 'backend:masterdata',
 'A support ticket moved status (start, resolve, cancel, close review or desk close). Resolution notes and cancel reasons are not in the payload.',
 '{"type":"object","required":["ticket_uuid","from_status","to_status"],"properties":{
   "ticket_uuid":{"type":"string","format":"uuid"},
   "event_uuid":{"type":["string","null"],"format":"uuid","description":"support_ticket_event.client_uuid of the change"},
   "from_status":{"type":"string","enum":["new","in_progress","resolved","closed","cancelled"]},
   "to_status":{"type":"string","enum":["new","in_progress","resolved","closed","cancelled"]},
   "resolution_code":{"type":["string","null"],"description":"code list ticket_resolution_code, on a resolve"},
   "actor_user_id":{"type":["integer","null"]},
   "actor_is_support":{"type":"boolean"}}}', 'V0134'),
('support_ticket.commented', 1, 'support_ticket', 'support_ticket.client_uuid', 'backend:masterdata',
 'A comment was added to an open support ticket by the reporter (sync) or the desk (web). The comment text is not in the payload.',
 '{"type":"object","required":["ticket_uuid","event_uuid","actor_is_support"],"properties":{
   "ticket_uuid":{"type":"string","format":"uuid"},
   "event_uuid":{"type":"string","format":"uuid","description":"support_ticket_event.client_uuid"},
   "actor_user_id":{"type":["integer","null"]},
   "actor_is_support":{"type":"boolean"},
   "attachment_count":{"type":"integer"}}}', 'V0134'),
('zone_day.final_submitted', 1, 'zone_day', '<zone_id>:<business_date>', 'backend:sync',
 'A zone-day was final-submitted by a TSO (app or web) or by the midnight AutoFinalSubmitJob (via job); its route-days lock and later rows are held or refused (D-TSO-MAN-31).',
 '{"type":"object","required":["zone_id","business_date","final_submit_uuid","via"],"properties":{
   "zone_id":{"type":"integer"},"business_date":{"type":"string","format":"date"},
   "final_submit_uuid":{"type":"string","format":"uuid","description":"final_submit.client_uuid"},
   "via":{"type":"string","enum":["app","web","job"]},
   "submitted_by":{"type":["integer","null"],"description":"null for the midnight job"},
   "submit_seq":{"type":"integer","description":"reopened submits before this one"},
   "route_count":{"type":"integer"},"not_uploaded_route_count":{"type":"integer"}}}', 'V0134'),
('zone_day.reopened', 1, 'zone_day', '<zone_id>:<business_date>', 'backend:sync',
 'A final-submitted zone-day was reopened by an admin within the reopen window; its aggregates are recomputed. The reason is not in the payload.',
 '{"type":"object","required":["zone_id","business_date","final_submit_uuid","reopened_by"],"properties":{
   "zone_id":{"type":"integer"},"business_date":{"type":"string","format":"date"},
   "final_submit_uuid":{"type":"string","format":"uuid","description":"the reopened final_submit.client_uuid"},
   "reopen_uuid":{"type":["string","null"],"format":"uuid","description":"final_submit.reopen_client_uuid"},
   "reopened_by":{"type":"integer"},
   "route_day_count":{"type":"integer"}}}', 'V0134'),
('leave.decided', 1, 'leave_application', 'leave_application.client_uuid', 'backend:masterdata',
 'A leave application was approved or rejected on the web. The reason and the decision note are not in the payload.',
 '{"type":"object","required":["leave_uuid","user_id","status","decided_by"],"properties":{
   "leave_uuid":{"type":"string","format":"uuid","description":"leave_application.client_uuid"},
   "user_id":{"type":"integer","description":"the applicant"},
   "status":{"type":"string","enum":["approved","rejected"]},
   "decided_by":{"type":"integer"},
   "leave_type_code":{"type":"string","description":"code list leave_type"},
   "from_date":{"type":"string","format":"date"},"to_date":{"type":"string","format":"date"},
   "days":{"type":"integer"}}}', 'V0134');

SELECT app.apply_db_role_grants();
