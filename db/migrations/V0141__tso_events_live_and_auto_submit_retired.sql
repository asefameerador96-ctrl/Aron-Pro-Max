-- V0141 TSO manual build, integration round (docs/requests/tso-manual-build-2026-10.md s10, lead ruling R-W-07; lane X1).
--
-- 1. Six domain-event types catalogued planned by V0134 (D-TSO-MAN-38) have their producers on the integration branch:
--    support_ticket.created, support_ticket.status_changed and support_ticket.commented (lane B1, the ticket handler and
--    the desk), zone_day.final_submitted and zone_day.reopened (lane B3, DayService: the TSO, delegate and midnight job
--    Final Submit, and the audited reopen) and leave.decided (lane B3, the leave decision). Their status becomes live
--    (V0117: a backend producer writes the type today). Only the status moves; the published payload schemas, versions
--    and enforce_required stay as V0134 wrote them (the catalogue guard allows status, V0117). docs/data-events.md
--    renders the status (DataEventsTest).
-- 2. dw.agg_daily_zone.auto_submitted is retired. The midnight job writes a real app.final_submit row (via = 'job',
--    V0135, D-TSO-MAN-30), the zone-day projection is rebuilt from live app.final_submit rows only and always writes
--    false (DashboardSummaries.rebuildZoneDay, D-B2-18, ratified by R-W-03), and a row still true is never a Final Submit
--    (D-B2-24). The column stays (dw.v_route_day.zone_auto_submitted reads it; the stable view contract is not narrowed);
--    its comment, and the view column's, say it is always false and that app.final_submit is the source.
-- 3. One-off clean-up of the stamps the removed dw-only midnight worker left before this build (lane B2 ask 10): every
--    zone-day row still auto_submitted gets the projection the rebuild would write, final_submitted_at from the first
--    live app.final_submit row of that zone-day (null when there is none, or only reopened ones) and auto_submitted
--    false, so dw.agg_daily_zone and dw.v_route_day.zone_auto_submitted stop showing them. The figures do not need it
--    (they ignore such rows already, D-B2-24); it makes the stored rows say what the figures say. Rows already false
--    are not touched. The statement is idempotent: a second run finds no row.
-- No DDL on a table: the UPDATEs take row locks only; lock_timeout as every migration (squawk).

SET lock_timeout = '5s';

-- ---------- 1. producers shipped: the six TSO events are live ----------
UPDATE app.domain_event_type SET status = 'live'
 WHERE event_type IN ('support_ticket.created', 'support_ticket.status_changed', 'support_ticket.commented',
                      'zone_day.final_submitted', 'zone_day.reopened', 'leave.decided')
   AND status <> 'live';

-- ---------- 2. auto_submitted retired ----------
COMMENT ON COLUMN dw.agg_daily_zone.auto_submitted IS 'Retired (V0141, D-TSO-MAN-30): always false. The midnight job writes a real app.final_submit row (via job), and app.final_submit is the only source of a zone-day''s Final Submit; final_submitted_at is the first live app.final_submit row. Kept so the stable dw view keeps its column.';
COMMENT ON COLUMN dw.v_route_day.zone_auto_submitted IS 'Retired (V0141): always false (dw.agg_daily_zone.auto_submitted). A midnight-job Final Submit is a live app.final_submit row (via job) and shows in zone_final_submitted_at like any other.';

-- ---------- 3. one-off clean-up of the old dw-only stamps (B2 ask 10) ----------
UPDATE dw.agg_daily_zone z
   SET final_submitted_at = (SELECT min(f.submitted_at) FROM app.final_submit f
                              WHERE f.zone_id = z.zone_id AND f.business_date = z.business_date AND f.reopened_at IS NULL),
       auto_submitted = false
 WHERE z.auto_submitted;
