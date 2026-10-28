-- V0140 stable dw views of the TSO writes (contract docs/requests/tso-manual-build-2026-10.md s2.5; D-TSO-MAN-38, rule
-- 12; gap items DP-01..05, TK-20, LV-22, DASH-04; lane F2). Dashboards and the data lake read only dw.v_* views (docs/31
-- s3), so each TSO capture gets one: tickets, leave, feedback, visit plan outlets (Set Plan and Visit Query
-- completion), call assessments (joint calls and Visit Query) and tasks (Assign Task). No free text: titles,
-- descriptions, reasons, notes and comment bodies stay in app (pii: none here).
-- Column order follows the other views: data_origin (V0116), then is_effective last (V0118, DwViewContractTest).
-- is_effective = the row is not data-voided (voided_at IS NULL; for a plan outlet, neither it nor its plan).
-- Grants: the v_* rows of the grant map (web_ro, bi_reader; api_rw reads all of dw); app.apply_db_role_grants() below.
-- dw.view_contract_version() 1.2 -> 1.3 (views added: minor).
-- DASH-04 / D-TSO-MAN-08: dw.v_calendar and dw.v_calendar_month stay global (global holidays, Friday weekend); their
-- comments now say so and that the API applies scoped holidays and a configured weekend (WorkingDays).

SET lock_timeout = '5s';

-- ---------- tickets (DP via TK-20) ----------
CREATE VIEW dw.v_support_ticket AS
SELECT t.client_uuid AS ticket_uuid, t.ticket_no, t.business_date, t.created_at, t.user_id, s.requester_role,
       t.issue_type_code, t.source, s.priority, coalesce(s.status, 'new') AS status,
       s.first_response_at, s.resolved_at, s.closed_at, s.cancelled_at, s.resolution_code,
       (t.affected_username IS NOT NULL) AS has_affected_user,
       t.data_origin, (t.voided_at IS NULL) AS is_effective
  FROM app.support_ticket t
  LEFT JOIN app.support_ticket_state s ON s.ticket_uuid = t.client_uuid;

-- ---------- leave (DP-02, LV-22) ----------
CREATE VIEW dw.v_leave_application AS
SELECT l.client_uuid AS leave_uuid, l.user_id, l.leave_type_code, l.from_date, l.to_date, l.days, l.status,
       l.captured_at AS applied_at, l.decided_at, l.decided_by AS decided_by_user_id,
       l.data_origin, (l.voided_at IS NULL) AS is_effective
  FROM app.leave_application l;

-- ---------- feedback (DP-01) ----------
CREATE VIEW dw.v_feedback AS
SELECT f.client_uuid AS feedback_uuid, f.business_date, f.user_id, f.category_code,
       coalesce(s.status, 'new') AS status, (f.photo_uuid IS NOT NULL) AS has_photo,
       f.data_origin, (f.voided_at IS NULL) AS is_effective
  FROM app.feedback f
  LEFT JOIN app.feedback_status s ON s.feedback_client_uuid = f.client_uuid;

-- ---------- visit plan outlets (DP-03, SP-03) ----------
CREATE VIEW dw.v_visit_plan_outlet AS
SELECT po.plan_client_uuid AS plan_uuid, p.plan_date, p.user_id AS planner_user_id,
       coalesce(p.route_id, (SELECT min(o.route_id) FROM app.visit_plan_outlet x JOIN app.outlet o ON o.id = x.outlet_id
                              WHERE x.plan_client_uuid = p.client_uuid AND x.voided_at IS NULL)) AS route_id,
       po.outlet_id, q.captured_at AS completed_at, q.client_uuid AS assessment_uuid,
       GREATEST(po.data_origin, p.data_origin) AS data_origin,
       (po.voided_at IS NULL AND p.voided_at IS NULL) AS is_effective
  FROM app.visit_plan_outlet po
  JOIN app.visit_plan p ON p.client_uuid = po.plan_client_uuid
  LEFT JOIN LATERAL (
    SELECT a.client_uuid, a.captured_at FROM app.call_assessment a
     WHERE a.visit_plan_outlet_client_uuid = po.client_uuid AND a.kind = 'retailer_questionnaire' AND a.voided_at IS NULL
     ORDER BY a.captured_at, a.id LIMIT 1) q ON true;

-- ---------- call assessments (DP-04) ----------
-- The outlet comes from the planned outlet (Visit Query) or the visit (joint call); visit client UUIDs are unique
-- across business dates (V0021 visit_client_uuid_once).
CREATE VIEW dw.v_call_assessment AS
SELECT a.client_uuid AS assessment_uuid, a.kind, a.business_date, a.user_id, a.route_id,
       coalesce(po.outlet_id, v.outlet_id) AS outlet_id, a.rubric_id, a.rubric_version, a.total_score, a.max_score,
       a.delegate_task, a.data_origin, (a.voided_at IS NULL) AS is_effective
  FROM app.call_assessment a
  LEFT JOIN app.visit_plan_outlet po ON po.client_uuid = a.visit_plan_outlet_client_uuid
  LEFT JOIN app.visit v ON v.client_uuid = a.visit_client_uuid;

-- ---------- tasks (DP-05) ----------
CREATE VIEW dw.v_task AS
SELECT t.client_uuid AS task_uuid, t.business_date, t.task_type_code, t.user_id AS creator_user_id, t.assignee_user_id,
       t.outlet_id, t.route_id, t.due_date, t.status, t.status_changed_at,
       t.data_origin, (t.voided_at IS NULL) AS is_effective
  FROM app.task t;

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.3' $$;

-- ---------- comments ----------
COMMENT ON VIEW dw.v_support_ticket IS 'Stable view: one row per support ticket with its current status, priority and lifecycle times (app.support_ticket and app.support_ticket_state); no title, description or notes. is_effective = not data-voided.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_leave_application IS 'Stable view: one row per leave application with its dates, day count and decision (app.leave_application); no reason or decision note. is_effective = not data-voided.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_feedback IS 'Stable view: one row per TSO feedback with its category and inbox status (app.feedback, app.feedback_status); no title or description. is_effective = not data-voided.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_visit_plan_outlet IS 'Stable view: one row per outlet of a TSO visit plan (Set Plan) with the plan''s date and route and the first Visit Query that completed it. is_effective = neither the outlet row nor its plan is data-voided.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_call_assessment IS 'Stable view: one row per call assessment (AMO joint call or TSO Visit Query) with its rubric version and score; the answers stay in app. is_effective = not data-voided.
owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_task IS 'Stable view: one row per task (TSO Assign Task, AMO or web task) with its type, creator, assignee, outlet, route, due date and status; no title or description. is_effective = not data-voided.
owner: backend:notify | capture: OFFLINE | retention: transaction | pii: none';

COMMENT ON COLUMN dw.v_support_ticket.ticket_uuid IS 'Client UUID of the ticket (app.support_ticket.client_uuid).';
COMMENT ON COLUMN dw.v_support_ticket.ticket_no IS 'Ticket number INC + at least 6 digits.';
COMMENT ON COLUMN dw.v_support_ticket.business_date IS 'Asia/Dhaka business date the ticket was raised.';
COMMENT ON COLUMN dw.v_support_ticket.created_at IS 'UTC instant the server stored the ticket.';
COMMENT ON COLUMN dw.v_support_ticket.user_id IS 'Reporter (app.app_user id).';
COMMENT ON COLUMN dw.v_support_ticket.requester_role IS 'Role of the reporter when the ticket was raised.';
COMMENT ON COLUMN dw.v_support_ticket.issue_type_code IS 'Issue type (code list ticket_issue_type).';
COMMENT ON COLUMN dw.v_support_ticket.source IS 'quick (Quick Ticket tile) or manual (Create Ticket form).';
COMMENT ON COLUMN dw.v_support_ticket.priority IS 'Current priority p1 (Highest) to p4 (Low).';
COMMENT ON COLUMN dw.v_support_ticket.status IS 'Current status: new, in_progress, resolved, closed or cancelled.';
COMMENT ON COLUMN dw.v_support_ticket.first_response_at IS 'UTC time of the first support action; null before it.';
COMMENT ON COLUMN dw.v_support_ticket.resolved_at IS 'UTC time support resolved the ticket; null before it.';
COMMENT ON COLUMN dw.v_support_ticket.closed_at IS 'UTC time the ticket was closed; null before it.';
COMMENT ON COLUMN dw.v_support_ticket.cancelled_at IS 'UTC time support cancelled the ticket; null unless cancelled.';
COMMENT ON COLUMN dw.v_support_ticket.resolution_code IS 'Resolution code (code list ticket_resolution_code); null until resolved.';
COMMENT ON COLUMN dw.v_support_ticket.has_affected_user IS 'True when the ticket was raised for another user (affected_username set).';
COMMENT ON COLUMN dw.v_support_ticket.data_origin IS 'Origin of the ticket (app.support_ticket.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_support_ticket.is_effective IS 'True when the ticket is not data-voided (voided_at is null).';

COMMENT ON COLUMN dw.v_leave_application.leave_uuid IS 'Client UUID of the leave application.';
COMMENT ON COLUMN dw.v_leave_application.user_id IS 'Applicant (app.app_user id).';
COMMENT ON COLUMN dw.v_leave_application.leave_type_code IS 'Leave type (code list leave_type).';
COMMENT ON COLUMN dw.v_leave_application.from_date IS 'First day of the leave.';
COMMENT ON COLUMN dw.v_leave_application.to_date IS 'Last day of the leave (from_date + days - 1, calendar days).';
COMMENT ON COLUMN dw.v_leave_application.days IS 'Number of calendar days of the leave.';
COMMENT ON COLUMN dw.v_leave_application.status IS 'pending, approved or rejected.';
COMMENT ON COLUMN dw.v_leave_application.applied_at IS 'UTC time the applicant submitted the application (capture time).';
COMMENT ON COLUMN dw.v_leave_application.decided_at IS 'UTC time of the approval or rejection; null while pending.';
COMMENT ON COLUMN dw.v_leave_application.decided_by_user_id IS 'User who decided (app.app_user id); null while pending.';
COMMENT ON COLUMN dw.v_leave_application.data_origin IS 'Origin of the application (app.leave_application.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_leave_application.is_effective IS 'True when the application is not data-voided (voided_at is null).';

COMMENT ON COLUMN dw.v_feedback.feedback_uuid IS 'Client UUID of the feedback.';
COMMENT ON COLUMN dw.v_feedback.business_date IS 'Asia/Dhaka business date the feedback was written.';
COMMENT ON COLUMN dw.v_feedback.user_id IS 'Author (app.app_user id).';
COMMENT ON COLUMN dw.v_feedback.category_code IS 'Feedback category (code list feedback_category: suggestion or feedback).';
COMMENT ON COLUMN dw.v_feedback.status IS 'Support-inbox status (app.feedback_status), new when the inbox has not touched it.';
COMMENT ON COLUMN dw.v_feedback.has_photo IS 'True when a gallery image is attached.';
COMMENT ON COLUMN dw.v_feedback.data_origin IS 'Origin of the feedback (app.feedback.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_feedback.is_effective IS 'True when the feedback is not data-voided (voided_at is null).';

COMMENT ON COLUMN dw.v_visit_plan_outlet.plan_uuid IS 'Client UUID of the visit plan (app.visit_plan.client_uuid).';
COMMENT ON COLUMN dw.v_visit_plan_outlet.plan_date IS 'Date the TSO plans to visit the outlet.';
COMMENT ON COLUMN dw.v_visit_plan_outlet.planner_user_id IS 'TSO who set the plan (app.app_user id).';
COMMENT ON COLUMN dw.v_visit_plan_outlet.route_id IS 'Route chosen in Set Plan (app.visit_plan.route_id); for a plan stored before V0137, the smallest current route of the plan''s outlets.';
COMMENT ON COLUMN dw.v_visit_plan_outlet.outlet_id IS 'Planned outlet (app.outlet id).';
COMMENT ON COLUMN dw.v_visit_plan_outlet.completed_at IS 'Capture time of the first Visit Query (retailer_questionnaire assessment) for this planned outlet; null while pending.';
COMMENT ON COLUMN dw.v_visit_plan_outlet.assessment_uuid IS 'Client UUID of that Visit Query (dw.v_call_assessment.assessment_uuid); null while pending.';
COMMENT ON COLUMN dw.v_visit_plan_outlet.data_origin IS 'Origin (production, pilot, test, seed): the most synthetic of the plan outlet row and its plan.';
COMMENT ON COLUMN dw.v_visit_plan_outlet.is_effective IS 'True when neither the plan outlet row nor its plan is data-voided.';

COMMENT ON COLUMN dw.v_call_assessment.assessment_uuid IS 'Client UUID of the assessment.';
COMMENT ON COLUMN dw.v_call_assessment.kind IS 'joint_call (AMO with the SR) or retailer_questionnaire (TSO Visit Query).';
COMMENT ON COLUMN dw.v_call_assessment.business_date IS 'Asia/Dhaka business date of the assessment.';
COMMENT ON COLUMN dw.v_call_assessment.user_id IS 'Assessor (app.app_user id).';
COMMENT ON COLUMN dw.v_call_assessment.route_id IS 'Route of the assessment (envelope route).';
COMMENT ON COLUMN dw.v_call_assessment.outlet_id IS 'Outlet assessed: the planned outlet of a Visit Query, else the outlet of the visit of a joint call; null when neither is known.';
COMMENT ON COLUMN dw.v_call_assessment.rubric_id IS 'Rubric used (app.rubric id).';
COMMENT ON COLUMN dw.v_call_assessment.rubric_version IS 'Version of the rubric used.';
COMMENT ON COLUMN dw.v_call_assessment.total_score IS 'Score reached (0 for a text-only questionnaire).';
COMMENT ON COLUMN dw.v_call_assessment.max_score IS 'Highest possible score of the rubric version.';
COMMENT ON COLUMN dw.v_call_assessment.delegate_task IS 'True when the assessor chose to delegate a task from it.';
COMMENT ON COLUMN dw.v_call_assessment.data_origin IS 'Origin of the assessment (app.call_assessment.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_call_assessment.is_effective IS 'True when the assessment is not data-voided (voided_at is null).';

COMMENT ON COLUMN dw.v_task.task_uuid IS 'Client UUID of the task.';
COMMENT ON COLUMN dw.v_task.business_date IS 'Asia/Dhaka business date the task was created.';
COMMENT ON COLUMN dw.v_task.task_type_code IS 'Task type (code list task_type).';
COMMENT ON COLUMN dw.v_task.creator_user_id IS 'User who created the task (app.app_user id).';
COMMENT ON COLUMN dw.v_task.assignee_user_id IS 'User the task is assigned to (app.app_user id).';
COMMENT ON COLUMN dw.v_task.outlet_id IS 'Outlet the task is about; null when none.';
COMMENT ON COLUMN dw.v_task.route_id IS 'Route the task belongs to; null when the task names none.';
COMMENT ON COLUMN dw.v_task.due_date IS 'Due date; null when none.';
COMMENT ON COLUMN dw.v_task.status IS 'ongoing, completed or cancelled.';
COMMENT ON COLUMN dw.v_task.status_changed_at IS 'UTC time of the last status change; null while never changed.';
COMMENT ON COLUMN dw.v_task.data_origin IS 'Origin of the task (app.task.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_task.is_effective IS 'True when the task is not data-voided (voided_at is null).';

COMMENT ON VIEW dw.v_calendar IS 'Stable view: one row per date with Friday, national holiday, make-up day and working-day flags (dim_date plus the live global app.calendar_holiday rows). Global holidays and the default Friday weekend only: scoped holidays and a configured weekend are applied by the API (WorkingDays, D-TSO-MAN-08); tested equal for global scope.
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_calendar_month IS 'Stable view: per month the number of days, Fridays, national holidays and working days (divisor of the daily target). Global holidays and the default Friday weekend only: scoped holidays and a configured weekend are applied by the API (WorkingDays, D-TSO-MAN-08); tested equal for global scope.
owner: worker | capture: SERVER | retention: master | pii: none';

SELECT app.apply_db_role_grants();
