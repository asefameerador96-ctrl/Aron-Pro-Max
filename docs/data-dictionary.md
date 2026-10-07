# Data dictionary

Generated from the PostgreSQL catalogue by `tools/data-dictionary` (the `COMMENT ON` of every migration). Do not edit
by hand: change the comment in a new migration and regenerate. `DataDictionaryTest` fails when a table, view or
column has no comment or when this file is stale.

Conventions: money is integer milli-taka (`*_mtk`, 1 Tk = 1000 mtk); quantities are integers in the SKU base unit;
instants are UTC `timestamptz`; `business_date` is the Asia/Dhaka date. **Capture**: OFFLINE (device record, synced),
ONLINE (web or API write), SERVER (derived by the server or worker), REFERENCE (seeded by migration). **Retention**:
the class of docs/16 s13.1. **PII**: none, personal, sensitive, secret. Other products read only the `dw` views
(`v_*`, stable contract, additive changes only) and the domain-event outbox, never `app` tables (docs/31 s3).

| Schema | Relations | Columns |
|---|---|---|
| `app` | 146 | 2540 |
| `dw` | 33 | 512 |

## Index

| Relation | Kind | Owner | Capture | Retention | PII | Description |
|---|---|---|---|---|---|---|
| [`app.activity_log`](#appactivity_log) | table | backend:platform | OFFLINE | telemetry | none | One row is a batch of sampled screen and action events from a phone, kept for support and usage analysis. |
| [`app.admin_asset`](#appadmin_asset) | table | backend:masterdata | ONLINE | master | none | One row is a file an admin uploaded (content video or image, tutorial, SKU or gift image), stored in Blob. |
| [`app.app_error`](#appapp_error) | table | backend:platform | OFFLINE | telemetry | none | One row is a scrubbed crash, ANR or handled-error report sent by a phone. |
| [`app.app_package`](#appapp_package) | table | backend:config | ONLINE | master | none | Catalogue of Android packages the portal offers when editing the app block and allow lists, with a category and a suggested rule. |
| [`app.app_release`](#appapp_release) | table | backend:config | ONLINE | master | none | One published or draft build of the field app (version, ABI, download URL, signing certificate, rollout percentage). |
| [`app.app_user`](#appapp_user) | table | backend:masterdata | ONLINE | master | secret | One person who can log in, field or web, with role, status, locale and Argon2id password hash; never deleted. |
| [`app.archive_manifest`](#apparchive_manifest) | table | db | SERVER | audit | none | One row per exported month partition: planned, exported, verified, dropped, restored; a partition is dropped only after its row is verified. |
| [`app.astha_target`](#appastha_target) | table | backend:masterdata | ONLINE | master | none | Astha programme target for a route (optionally an outlet and brand) and month, in standard units and memo count. |
| [`app.attendance_event`](#appattendance_event) | table | backend:sync | OFFLINE | transaction | personal | One check-in or check-out of a user with its on-demand location fix. |
| [`app.audit_log`](#appaudit_log) | table | backend:platform | SERVER | audit | personal | Append-only, hash-chained record of web and admin actions with before and after images and the actor. |
| [`app.auth_lockout`](#appauth_lockout) | table | backend:auth | SERVER | session | none | Failed-login counter and lock state per lock key, shared by every API replica. |
| [`app.bundle_snapshot`](#appbundle_snapshot) | table | backend:sync | SERVER | ops | none | One row is a distinct day-bundle content the server generated for a user and business date, numbered in order. |
| [`app.calendar_holiday`](#appcalendar_holiday) | table | backend:masterdata | ONLINE | master | none | One declared holiday or selling-day override for a geography scope and date. |
| [`app.call_assessment`](#appcall_assessment) | table | backend:sync | OFFLINE | transaction | none | One AMO or TSO joint-call assessment or retailer questionnaire for a visit, with total and maximum score. |
| [`app.call_assessment_answer`](#appcall_assessment_answer) | table | backend:sync | OFFLINE | transaction | personal | One criterion answer (score, text or yes/no) of a call assessment. |
| [`app.cfg_ack`](#appcfg_ack) | table | backend:config | OFFLINE | telemetry | none | Record from a phone saying which config version it applied and which keys changed. |
| [`app.cfg_change`](#appcfg_change) | table | backend:config | ONLINE | audit | none | One config change request with its items, risk class, approvals and apply time; part of the maker-checker trail. |
| [`app.cfg_key`](#appcfg_key) | table | backend:config | REFERENCE | master | none | Registry of every config key with type, default, bounds, scope levels, risk class and delivery; seeded by migration, never deleted. |
| [`app.cfg_value`](#appcfg_value) | table | backend:config | ONLINE | master | none | One scoped value of a config key valid for a period; rows are closed, never edited. |
| [`app.cfg_version`](#appcfg_version) | table | backend:config | ONLINE | audit | none | One committed config version; versions are global and increase monotonically. |
| [`app.cluster`](#appcluster) | table | backend:masterdata | ONLINE | master | none | A named group of outlets within a zone, used to organise routes. |
| [`app.code_list`](#appcode_list) | table | backend:masterdata | REFERENCE | master | none | Header of a business code list (reasons, outcomes, channels and similar) with its key. |
| [`app.code_list_item`](#appcode_list_item) | table | backend:masterdata | ONLINE | master | none | One code of a business code list with English and Bangla labels; codes never change, only retire. |
| [`app.content_item`](#appcontent_item) | table | backend:masterdata | ONLINE | master | none | An audio-visual or key-visual item played during sales calls, with its asset, size and version. |
| [`app.content_view`](#appcontent_view) | table | backend:sync | OFFLINE | telemetry | none | One showing or skipping of a content item during a call. |
| [`app.day_exception`](#appday_exception) | table | backend:sync | OFFLINE | transaction | personal | A rain, hartal or other day exception raised from the field for routes and dates, decided by the zone TSO. |
| [`app.db_role_grant`](#appdb_role_grant) | table | db | REFERENCE | master | none | Least-privilege grant map of the database roles; app.apply_db_role_grants() generates every GRANT from it. |
| [`app.db_role_limit`](#appdb_role_limit) | table | db | REFERENCE | master | none | Session limits per privilege role (docs/18 s2.6); app.apply_login_limits() writes them onto the login identities. |
| [`app.device`](#appdevice) | table | backend:auth | ONLINE | master | none | One enrolled phone with its status, trust level, key, integrity verdict and last contact. |
| [`app.device_binding`](#appdevice_binding) | table | backend:auth | ONLINE | audit | none | Link of a user to a phone, with the binding ordinal that fixes the memo-number block. |
| [`app.device_directive`](#appdevice_directive) | table | backend:config | ONLINE | ops | none | A signed remote directive sent to a phone, with delivery and acknowledgement times. |
| [`app.device_nonce`](#appdevice_nonce) | table | backend:auth | SERVER | session | none | Single-use server nonce for a Play Integrity request or key attestation. |
| [`app.device_otp`](#appdevice_otp) | table | backend:auth | ONLINE | session | secret | One-time code a TSO issues to bind a phone to a user, stored as hash and encrypted for display. |
| [`app.device_policy`](#appdevice_policy) | table | backend:config | SERVER | ops | none | The device policy rendered for a phone at a given policy version, with its hash. |
| [`app.device_status_report`](#appdevice_status_report) | table | backend:auth | OFFLINE | telemetry | none | One report of a phone's lockdown, settings, pending rows and battery state. |
| [`app.dirty_key`](#appdirty_key) | table | backend:platform | SERVER | ops | none | Work queue entry naming a bundle snapshot or aggregate row that the worker must rebuild. |
| [`app.distribution_check`](#appdistribution_check) | table | backend:sync | OFFLINE | transaction | none | One AMO or TSO distribution check of a visited outlet. |
| [`app.distribution_check_line`](#appdistribution_check_line) | table | backend:sync | OFFLINE | transaction | none | Presence and out-of-stock flags for one brand in a distribution check. |
| [`app.division`](#appdivision) | table | backend:masterdata | ONLINE | master | none | Geography level below a wing; groups territories. |
| [`app.domain_event`](#appdomain_event) | partitioned table | backend:platform | SERVER | ops | none | Append-only outbox of events written with each change; the projector and later consumers read it in id order. |
| [`app.domain_event_type`](#appdomain_event_type) | table | db | REFERENCE | master | none | Catalogue of domain-event types and payload versions; every outbox row must name one (docs/data-events.md). |
| [`app.due_collection`](#appdue_collection) | table | backend:sync | OFFLINE | transaction | personal | Cash collected against an earlier credit memo, with the collecting location fix. |
| [`app.due_ledger`](#appdue_ledger) | table | backend:sync | SERVER | transaction | none | Append-only ledger of an outlet's outstanding dues: credit memos and opening balances raise it, collections and voids lower it. |
| [`app.enrolment_token`](#appenrolment_token) | table | backend:auth | ONLINE | session | none | A one-time or limited-use token that lets a phone enrol; only its hash is stored. |
| [`app.entry_unlock`](#appentry_unlock) | table | backend:masterdata | ONLINE | transaction | none | One row is a time-limited unlock that lets web entry be back-dated for a zone or route over a date range. |
| [`app.event_consumer`](#appevent_consumer) | table | backend:platform | SERVER | ops | none | Read position of each domain-event consumer. |
| [`app.feedback`](#appfeedback) | table | backend:masterdata | OFFLINE | transaction | personal | Free-text feedback or problem report sent from the TSO app, with optional photo. |
| [`app.feedback_status`](#appfeedback_status) | table | backend:masterdata | ONLINE | transaction | none | One row is the support-inbox status of a feedback item; a feedback item without a row is new. |
| [`app.final_submit`](#appfinal_submit) | table | backend:sync | ONLINE | transaction | none | Final Submit of a zone and business date, made online by the TSO, unless reopened. |
| [`app.geo_breadcrumb`](#appgeo_breadcrumb) | partitioned table | backend:sync | OFFLINE | fix | personal | Batched low-power location points recorded only while breadcrumbs are enabled by config. |
| [`app.geo_class_def`](#appgeo_class_def) | table | db | REFERENCE | master | none | Lookup giving each geography class an integer ordinal for config scoping. |
| [`app.geo_fix`](#appgeo_fix) | partitioned table | backend:sync | OFFLINE | fix | personal | One location fix carried by a record, with accuracy, provider and sensor evidence used for the server re-check and risk rules. |
| [`app.gift`](#appgift) | table | backend:masterdata | ONLINE | master | none | A gift of a loyalty programme with points cost and tiers. |
| [`app.gift_assignment`](#appgift_assignment) | table | backend:masterdata | ONLINE | transaction | none | An Astha gift chosen for an outlet and quarter, locked by the hand-over photo. |
| [`app.gift_photo`](#appgift_photo) | table | backend:sync | OFFLINE | transaction | personal | Hand-over photo record of a gift, one per Astha assignment or redeemed campaign unit. |
| [`app.house`](#apphouse) | table | backend:masterdata | ONLINE | master | none | Distribution house that a zone may belong to; it carries no rules in Phase 1. |
| [`app.indent_movement`](#appindent_movement) | table | backend:masterdata | ONLINE | transaction | none | Phase 2 indent-portal stock ledger with the same shape as stock_movement; empty in Phase 1. |
| [`app.ingest_registry`](#appingest_registry) | partitioned table | backend:sync | SERVER | ops | none | Global register of every device record's client_uuid and payload hash; the uniqueness point for idempotent sync. |
| [`app.leave_application`](#appleave_application) | table | backend:masterdata | OFFLINE | transaction | personal | A TSO leave application with dates and reason, decided on the web. |
| [`app.loyalty_ledger`](#apployalty_ledger) | table | backend:analytics | SERVER | transaction | none | Append-only points ledger derived on the server, idempotent per source record. |
| [`app.media`](#appmedia) | table | backend:media | OFFLINE | transaction | personal | Metadata of a photo taken in the field with its location fix and Blob Storage path; the image itself is in Blob Storage. |
| [`app.memo`](#appmemo) | partitioned table | backend:sync | OFFLINE | transaction | none | One sales memo header (or zero-sale record) for an outlet visit, with gross, discount, net, paid and due amounts. |
| [`app.memo_discount`](#appmemo_discount) | table | backend:sync | OFFLINE | transaction | none | One discount component of a memo: offer, DRP or free goods, with SKU, quantity and value. |
| [`app.memo_line`](#appmemo_line) | partitioned table | backend:sync | OFFLINE | transaction | none | One SKU line of a memo with quantity, base price and gross amount. |
| [`app.memo_void`](#appmemo_void) | table | backend:sync | OFFLINE | transaction | personal | Void of a memo, recorded with its reason and location fix; the memo turns voided and is never deleted. |
| [`app.mfa_secret`](#appmfa_secret) | table | backend:auth | ONLINE | master | secret | A user's TOTP secret (encrypted) and recovery code hashes. |
| [`app.offer`](#appoffer) | table | backend:masterdata | ONLINE | master | none | Stable identity of an offer; each rule change is a new offer_version. |
| [`app.offer_version`](#appoffer_version) | table | backend:masterdata | ONLINE | master | none | One version of an offer's rule, validity and reward; memos record the versions they used. |
| [`app.offer_version_product`](#appoffer_version_product) | table | backend:masterdata | ONLINE | master | none | Product the offer version applies to, at category, brand, variant or SKU level. |
| [`app.offer_version_scope`](#appoffer_version_scope) | table | backend:masterdata | ONLINE | master | none | Geography or channel node an offer version is limited to; no rows means everywhere. |
| [`app.outlet`](#appoutlet) | table | backend:masterdata | ONLINE | master | sensitive | One retail or wholesale outlet with owner, location, route placement and national id, TIN and trade licence. |
| [`app.outlet_change_request`](#appoutlet_change_request) | table | backend:masterdata | OFFLINE | transaction | sensitive | A request from the field to add, close, edit, move or relocate an outlet, with proposed data and decision trail. |
| [`app.outlet_location_history`](#appoutlet_location_history) | table | backend:masterdata | SERVER | master | none | Append-only history of every map pin an outlet has had and where it came from. |
| [`app.outlet_placement_history`](#appoutlet_placement_history) | table | backend:masterdata | SERVER | master | none | History of an outlet's route and cluster placement over time. |
| [`app.outlet_programme`](#appoutlet_programme) | table | backend:analytics | SERVER | master | none | Programme eligibility of an outlet, shown as dots in the SR app outlet list. |
| [`app.outlet_request_event`](#appoutlet_request_event) | table | backend:masterdata | SERVER | audit | personal | Trail of create, verify, approve, reject and lapse events on an outlet change request. |
| [`app.partition_policy`](#apppartition_policy) | table | db | REFERENCE | ops | none | List of range-partitioned parent tables with the key column and months to create ahead. |
| [`app.password_history`](#apppassword_history) | table | backend:auth | ONLINE | master | secret | One row is a password hash a user replaced, kept to refuse re-use of the last cfg.auth.password_history_depth passwords. |
| [`app.pii_key`](#apppii_key) | table | backend:masterdata | SERVER | master | secret | One data-encryption key for outlet NID, TIN and trade licence, stored only wrapped by a Key Vault key (envelope encryption, D-107); never deleted. |
| [`app.pii_read_budget`](#apppii_read_budget) | table | backend:analytics | SERVER | ops | none | One row is the number of personal-data rows a user has read in one clock hour (hourly PII read budget). |
| [`app.price_batch`](#appprice_batch) | table | backend:masterdata | ONLINE | audit | none | One row is a batch of price changes through preview, approval and publish (maker-checker above the change threshold). |
| [`app.price_compliance_check`](#appprice_compliance_check) | table | backend:sync | OFFLINE | transaction | none | AMO check comparing the observed retail price of a SKU with the reference price. |
| [`app.print_event`](#appprint_event) | table | backend:sync | OFFLINE | transaction | none | One print attempt of a memo, reprint, stock slip, day summary, void slip or due receipt. |
| [`app.print_template`](#appprint_template) | table | backend:masterdata | ONLINE | master | none | One row is an immutable version of a thermal-print template for one slip kind, in force from effective_from. |
| [`app.product_node`](#appproduct_node) | table | backend:masterdata | ONLINE | master | none | One node of the product tree (category, segment, brand or variant). |
| [`app.programme`](#appprogramme) | table | backend:masterdata | ONLINE | master | none | A loyalty or incentive programme (Diamond League, Astha, campaign, Superstar) with period and rules. |
| [`app.programme_enrolment`](#appprogramme_enrolment) | table | backend:masterdata | ONLINE | master | none | Enrolment of an outlet in a programme for a period, with league, tier and base target. |
| [`app.push_token`](#apppush_token) | table | backend:notify | ONLINE | ops | secret | FCM push token of a user on a phone. |
| [`app.qc_entry`](#appqc_entry) | table | backend:sync | SERVER | transaction | none | QC header created by the server for a visit from its QC fault lines. |
| [`app.qc_entry_line`](#appqc_entry_line) | table | backend:sync | OFFLINE | transaction | none | One QC fault line (faulty units) of a visit, optionally deducted on the memo. |
| [`app.qc_summary_entry`](#appqc_summary_entry) | table | backend:masterdata | ONLINE | transaction | none | One row is a back-office QC summary entered on the web: market QC for a route or warehouse QC for a zone. |
| [`app.qc_summary_entry_line`](#appqc_summary_entry_line) | table | backend:masterdata | ONLINE | transaction | none | One row is a faulty quantity of one SKU and fault type in a web QC summary. |
| [`app.redemption`](#appredemption) | table | backend:sync | OFFLINE | transaction | personal | A loyalty redemption basket confirmed in the field; the server debits the loyalty ledger. |
| [`app.redemption_line`](#appredemption_line) | table | backend:sync | OFFLINE | transaction | none | One gift of a redemption basket with quantity and points. |
| [`app.refresh_family`](#apprefresh_family) | table | backend:auth | ONLINE | session | none | A login session: the family of refresh tokens issued from one login, with expiry and revoke state. |
| [`app.refresh_token`](#apprefresh_token) | table | backend:auth | ONLINE | session | secret | One refresh token of a family, stored only as a hash, with rotation links. |
| [`app.report_export`](#appreport_export) | table | backend:analytics | ONLINE | audit | none | One row is a report export (xlsx, pdf or print), synchronous or a queued job: who ran which report with which filters, how many rows and whether personal data was included. |
| [`app.retention_policy`](#appretention_policy) | table | db | REFERENCE | master | none | One row per retention class: months a partition stays in the primary database and months its export is kept (docs/16 s13.1, D-371). |
| [`app.risk_signal`](#apprisk_signal) | table | backend:analytics | SERVER | transaction | none | A risk signal (for example mock location or teleport) computed by the worker for a subject and business date. |
| [`app.risk_signal_review`](#apprisk_signal_review) | table | backend:analytics | OFFLINE | audit | personal | Review action on a risk signal by an AMO or web user; append-only. |
| [`app.role_def`](#approle_def) | table | db | REFERENCE | master | none | Lookup giving each role an integer ordinal for config scoping. |
| [`app.role_grant_map`](#approle_grant_map) | table | backend:auth | REFERENCE | master | none | Admin permission granted to each role, carried as the web permission claim. |
| [`app.route`](#approute) | table | backend:masterdata | ONLINE | master | none | A sales route in a zone with its code, kind, visit days and label. |
| [`app.route_assignment`](#approute_assignment) | table | backend:masterdata | ONLINE | master | none | Assignment of a user to a route as primary or cover for a date range. |
| [`app.route_day`](#approute_day) | table | backend:sync | SERVER | transaction | none | One row per route and business date holding the day state and submit timestamps; created by the server from assignments. |
| [`app.route_day_event`](#approute_day_event) | table | backend:sync | OFFLINE | transaction | none | A day_open or day_submit record from a phone or the online Sales Submit. |
| [`app.route_day_void_barrier`](#approute_day_void_barrier) | table | backend:sync | ONLINE | audit | none | Admin data void of a route-day: rows captured before the barrier are voided, later rows accepted. |
| [`app.route_planned`](#approute_planned) | table | backend:masterdata | ONLINE | master | none | Planned visit days of a route, effective-dated. |
| [`app.route_zone_history`](#approute_zone_history) | table | db | SERVER | master | none | One row is a period in which a route belonged to a zone; written by triggers on app.route. |
| [`app.rubric`](#apprubric) | table | backend:masterdata | ONLINE | master | none | One row is a scoring rubric (joint call or retailer questionnaire); its current version is in rubric_version. |
| [`app.rubric_version`](#apprubric_version) | table | backend:masterdata | ONLINE | master | none | One row is an immutable published version of a rubric: its scored criteria; assessments reference it. |
| [`app.sale_abort`](#appsale_abort) | table | backend:sync | OFFLINE | transaction | none | A memo number consumed without a memo, explaining gaps in memo numbering. |
| [`app.sales_plan`](#appsales_plan) | table | backend:masterdata | ONLINE | master | none | SKU enabled for a zone for a date range. |
| [`app.security_event`](#appsecurity_event) | table | backend:platform | SERVER | audit | personal | Append-only security events (login failures, lockouts, refresh reuse, device proof and state refusals, scope and password changes, force logout, OTP views) for alerts and support (docs/21 s8.1). |
| [`app.server_generation`](#appserver_generation) | table | backend:sync | SERVER | ops | none | Database lineage: one row per new generation after creation, failover or restore. |
| [`app.sku`](#appsku) | table | backend:masterdata | ONLINE | master | none | A sellable product with unit, pack size, names and category. |
| [`app.sku_price`](#appsku_price) | table | backend:masterdata | ONLINE | master | none | Effective-dated price of a SKU for one of the five price types. |
| [`app.stock_movement`](#appstock_movement) | table | backend:sync | OFFLINE | transaction | none | Append-only stock event for one SKU (issue, return, adjustment, damage and others) with signed quantity. |
| [`app.sub_channel`](#appsub_channel) | table | backend:masterdata | ONLINE | master | none | Outlet sub-channel within a channel. |
| [`app.submit_void_event`](#appsubmit_void_event) | table | backend:sync | ONLINE | audit | none | Append-only record of a Sales Submit being voided by a TSO or above. |
| [`app.supervisor_day`](#appsupervisor_day) | table | backend:sync | SERVER | transaction | none | Attendance and Sales Submit state of an AMO or TSO for a date outside a route-day. |
| [`app.support_upload`](#appsupport_upload) | table | backend:masterdata | OFFLINE | ops | none | One row is a phone database export a field user sent to Support (PDA to Support); the file is in Blob. |
| [`app.survey`](#appsurvey) | table | backend:masterdata | ONLINE | master | none | One row is a survey (POSM, AMO survey or TSO visit query); its current published version is in survey_version. |
| [`app.survey_response`](#appsurvey_response) | table | backend:sync | OFFLINE | transaction | personal | One answer to an in-visit survey question. |
| [`app.survey_version`](#appsurvey_version) | table | backend:masterdata | ONLINE | master | none | One row is an immutable published version of a survey: titles and questions; answers reference it. |
| [`app.sync_batch`](#appsync_batch) | table | backend:sync | SERVER | ops | none | Replay store of an uploaded batch with its fingerprint and stored response for the retention window. |
| [`app.sync_quarantine`](#appsync_quarantine) | table | backend:sync | SERVER | quarantine | personal | Records held for a human decision with their payload and resolution. |
| [`app.sync_rejected`](#appsync_rejected) | table | backend:sync | SERVER | quarantine | personal | Records rejected or parked at ingest with the payload as received and the reason code. |
| [`app.target`](#apptarget) | table | backend:masterdata | ONLINE | master | none | Live target of a route or zone for a product and month; revisions close and replace rows. |
| [`app.target_revision`](#apptarget_revision) | table | backend:masterdata | ONLINE | audit | none | One revision of a target set, with the change reason and stored result for idempotent replay. |
| [`app.target_set`](#apptarget_set) | table | backend:masterdata | ONLINE | master | none | Targets of a route or zone for one month. |
| [`app.task`](#apptask) | table | backend:notify | OFFLINE | transaction | personal | A task created by a supervisor for a user, optionally tied to an outlet or visit. |
| [`app.task_event`](#apptask_event) | table | backend:notify | OFFLINE | transaction | personal | Resolve or reopen of a task by its assignee. |
| [`app.territory`](#appterritory) | table | backend:masterdata | ONLINE | master | none | Geography level below a division; groups zones. |
| [`app.tutorial`](#apptutorial) | table | backend:masterdata | ONLINE | master | none | One row is a tutorial video or manual shown to the listed roles in the apps and on the web. |
| [`app.user_consent`](#appuser_consent) | table | backend:auth | OFFLINE | audit | none | Acceptance of a notice such as the location notice by a user on a phone. |
| [`app.user_scope`](#appuser_scope) | table | backend:masterdata | ONLINE | master | none | Supervisory reach of a user: a geography node, effective-dated. |
| [`app.v_dirty_key_dead`](#appv_dirty_key_dead) | view | db | SERVER | ops | none | Dead rebuild keys per kind, for the sync-health page and alerting. |
| [`app.visit`](#appvisit) | partitioned table | backend:sync | OFFLINE | transaction | personal | One outlet visit with the phone's and the server's geo verdicts, distance, outcome and close data. |
| [`app.visit_plan`](#appvisit_plan) | table | backend:masterdata | OFFLINE | transaction | none | A TSO visit plan for a date. |
| [`app.visit_plan_outlet`](#appvisit_plan_outlet) | table | backend:masterdata | OFFLINE | transaction | none | An outlet included in a TSO visit plan. |
| [`app.visit_skip`](#appvisit_skip) | table | backend:sync | OFFLINE | transaction | none | An outlet of the day's route not visited, with a reason. |
| [`app.web_entry_line`](#appweb_entry_line) | table | backend:masterdata | ONLINE | transaction | none | One row is the per-SKU quantities of a route-day web entry. |
| [`app.web_entry_route_day`](#appweb_entry_route_day) | table | backend:masterdata | ONLINE | transaction | none | One row is a back-office web entry of a route-day (issue, return and memos per SKU, successful calls); a re-save is a new row that closes the old one. |
| [`app.wing`](#appwing) | table | backend:masterdata | ONLINE | master | none | Top level of the sales geography. |
| [`app.zone`](#appzone) | table | backend:masterdata | ONLINE | master | none | Geography level below a territory; the unit of Final Submit and day rollups. |
| [`dw.agg_daily_outlet`](#dwagg_daily_outlet) | table | worker | SERVER | event_fact | none | Per outlet and business date: whether visited, geo-valid, and sales totals. |
| [`dw.agg_daily_route`](#dwagg_daily_route) | table | worker | SERVER | event_fact | none | Per route and business date: day state, visit counts, sales, discounts, paid and due money. |
| [`dw.agg_daily_route_brand`](#dwagg_daily_route_brand) | table | worker | SERVER | event_fact | none | Per route, brand and date: memo count containing the brand and its sales. |
| [`dw.agg_daily_route_segment`](#dwagg_daily_route_segment) | table | backend:analytics | SERVER | event_fact | none | Per route, product segment and date: memo count containing the segment (each memo once) and its sales. |
| [`dw.agg_daily_route_sku`](#dwagg_daily_route_sku) | table | worker | SERVER | event_fact | none | Per route, SKU and date: sold, free, issued and returned quantities and sales. |
| [`dw.agg_daily_screen_use`](#dwagg_daily_screen_use) | table | backend:analytics | SERVER | event_fact | none | One row is the use of one screen action by one role on one day, rolled up from fact_activity and kept for ever. |
| [`dw.agg_daily_zone`](#dwagg_daily_zone) | table | worker | SERVER | event_fact | none | Per zone and business date: route and visit counts, geo and risk counts, and sales totals. |
| [`dw.agg_hourly_zone`](#dwagg_hourly_zone) | table | worker | SERVER | event_fact | none | Per zone and hour: records received and sales, for the live dashboard. |
| [`dw.dim_date`](#dwdim_date) | table | db | REFERENCE | master | none | Calendar dimension keyed by date with week, month, quarter and holiday attributes. |
| [`dw.dim_geo`](#dwdim_geo) | table | worker | SERVER | master | none | Route-level geography dimension flattened through zone, territory, division and wing. |
| [`dw.dim_geo_version`](#dwdim_geo_version) | table | db | SERVER | master | none | One row is a version of geo valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_geo. |
| [`dw.dim_outlet`](#dwdim_outlet) | table | worker | SERVER | master | none | Outlet dimension for reports. |
| [`dw.dim_outlet_version`](#dwdim_outlet_version) | table | db | SERVER | master | none | One row is a version of outlet valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_outlet. |
| [`dw.dim_product`](#dwdim_product) | table | worker | SERVER | master | none | SKU dimension flattened through variant, brand, segment and category. |
| [`dw.dim_product_version`](#dwdim_product_version) | table | db | SERVER | master | none | One row is a version of product valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_product. |
| [`dw.dim_user`](#dwdim_user) | table | backend:analytics | SERVER | master | none | One row is the current state of a user for reporting (role, designation, home zone, status); no names or contacts. |
| [`dw.dim_user_version`](#dwdim_user_version) | table | db | SERVER | master | none | One row is a version of user valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_user. |
| [`dw.fact_activity`](#dwfact_activity) | partitioned table | backend:analytics | SERVER | telemetry | none | One row is one screen or action event from a phone's activity log. |
| [`dw.fact_attendance`](#dwfact_attendance) | table | worker | SERVER | event_fact | personal | One row per user and business date with the day's check-in and check-out and their fixes, filled by the worker from attendance_event. |
| [`dw.fact_consent`](#dwfact_consent) | table | backend:analytics | SERVER | audit | none | One row is a user's acceptance of a policy version (employee-location notice and other policies). |
| [`dw.fact_device_day`](#dwfact_device_day) | table | worker | SERVER | event_fact | none | Per device and business date: contact times, batch and record counts, rejects and battery low point. |
| [`dw.fact_device_integrity`](#dwfact_device_integrity) | partitioned table | backend:analytics | SERVER | event_fact | none | One row is a phone's integrity and readiness state observed at a login or bundle download. |
| [`dw.fact_geo_fix`](#dwfact_geo_fix) | partitioned table | worker | SERVER | event_fact | personal | One row per location fix copied for analysis, with slot and satellite count. |
| [`dw.fact_memo`](#dwfact_memo) | partitioned table | worker | SERVER | event_fact | none | One row per memo with its money totals, for reports. |
| [`dw.fact_visit`](#dwfact_visit) | partitioned table | worker | SERVER | event_fact | none | One row per visit with device and server verdicts, distance and void flag. |
| [`dw.v_attendance`](#dwv_attendance) | view | worker | SERVER | event_fact | none | Stable view: per user and business date, check-in and check-out times, hours in field and mock-location flags (no coordinates or accuracy). |
| [`dw.v_collections`](#dwv_collections) | view | worker | SERVER | event_fact | none | Stable view: outlet-days with new credit or dues collected (credit and collection movements). |
| [`dw.v_daily_outlet`](#dwv_daily_outlet) | view | worker | SERVER | event_fact | none | Stable view: per outlet and business date, visit, geo validity, sales and dues, with the outlet's classification. |
| [`dw.v_daily_route`](#dwv_daily_route) | view | worker | SERVER | event_fact | none | Stable view (contract for BI and other products): KPIs, money and day state per route and business date, with its geography. |
| [`dw.v_daily_sku`](#dwv_daily_sku) | view | worker | SERVER | event_fact | none | Stable view: per route, SKU and business date, quantities sold, free, issued and returned and the gross value. |
| [`dw.v_daily_sr`](#dwv_daily_sr) | view | worker | SERVER | event_fact | none | Stable view: per field user and business date, SR calls, successful calls, geo validity and memo money from the dw facts (active memos with lines); a user-day with neither an SR call nor such a memo has no row. |
| [`dw.v_geo_integrity`](#dwv_geo_integrity) | view | worker | SERVER | event_fact | none | Stable view: per user and business date, geo-validation outcomes of visits and mock-location evidence of fixes. |
| [`dw.v_outlet_masked`](#dwv_outlet_masked) | view | backend:masterdata | ONLINE | master | none | Stable view: outlets without owner name, address or national ids; the phone masked to 01*****NNN (D-107). |

## app.activity_log

One row is a batch of sampled screen and action events from a phone, kept for support and usage analysis.

`owner: backend:platform | capture: OFFLINE | retention: telemetry | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `events` | jsonb | not null |  | Array of sampled screen and action events (name, time, detail) from the phone. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.admin_asset

One row is a file an admin uploaded (content video or image, tutorial, SKU or gift image), stored in Blob.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `asset_id` | uuid | not null |  | Client-generated UUID of the upload; the API is idempotent by it. |
| `purpose` | text | not null |  | What the asset is for; allowed values are listed under constraints. |
| `mime` | text | not null |  | MIME type of the file; allowed values are listed under constraints. |
| `bytes` | bigint | not null |  | Size of the file in bytes (at most 100 MiB). |
| `sha256` | bytea | not null |  | SHA-256 of the file content (32 bytes). |
| `blob_path` | text | not null |  | Path of the file in the asset Blob container. |
| `uploaded_by` | bigint | null |  | User who uploaded the file. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `PRIMARY KEY (asset_id)`

References: `FOREIGN KEY (uploaded_by) REFERENCES app.app_user(id)`

## app.app_error

One row is a scrubbed crash, ANR or handled-error report sent by a phone.

`owner: backend:platform | capture: OFFLINE | retention: telemetry | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `occurred_at` | timestamp with time zone | not null |  | UTC time the error occurred on the phone. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `exception_class` | text | not null |  | Class name of the exception or ANR reason. |
| `message` | text | null |  | Scrubbed error message text, if any. |
| `stack` | text | null |  | Scrubbed stack trace text, if any. |
| `screen` | text | null |  | Name of the app screen that was open when the error occurred. |
| `app_version` | text | not null |  | App version as versionName+versionCode, e.g. 1.0.3+103. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.app_package

Catalogue of Android packages the portal offers when editing the app block and allow lists, with a category and a suggested rule.

`owner: backend:config | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `package_name` | text | not null |  | Android package name, for example com.example.app. |
| `label` | text | not null |  | Human-readable app name shown in the portal. |
| `category` | text | not null |  | Catalogue category such as social, video, game, messaging, maps or system. |
| `suggested_rule` | text | not null |  | Suggested handling in the device policy: block or allow. |
| `first_seen_at` | timestamp with time zone | null |  | UTC time a status report or install list first named the package. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (package_name)`

## app.app_release

One published or draft build of the field app (version, ABI, download URL, signing certificate, rollout percentage).

`owner: backend:config | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `flavour` | text | not null |  | Field app flavour: sr, amo or tso. |
| `version_name` | text | not null |  | Display version of the build, for example 1.4.2. |
| `version_code` | integer | not null |  | Android integer version code; increases with every build. |
| `abi` | text | not null |  | CPU architecture of the APK: universal, arm64-v8a or armeabi-v7a. |
| `sha256` | bytea | not null |  | SHA-256 of the file content. |
| `size_bytes` | integer | not null |  | APK size in bytes. |
| `download_url` | text | not null |  | URL the phone downloads the APK from. |
| `signing_cert_sha256` | bytea | not null |  | SHA-256 digest of the APK signing certificate. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `rollout_pct` | smallint | not null |  | Percentage of devices the release is offered to, 0 to 100. |
| `notes_en` | text | null |  | Release notes in English. |
| `notes_bn` | text | null |  | Release notes in Bangla. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `published_at` | timestamp with time zone | null |  | UTC time the release was published. |
| `published_by` | bigint | null |  | Id of the user who published the release; differs from its author. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (flavour, version_code, abi)`; `UNIQUE (sha256)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (published_by) REFERENCES app.app_user(id)`

## app.app_user

One person who can log in, field or web, with role, status, locale and Argon2id password hash; never deleted.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `username` | text | not null | personal | Login name; memo-producing roles embed it in memo numbers. |
| `full_name` | text | not null | personal | Full name of the user. |
| `role` | text | not null |  | User role (contract Role). |
| `designation` | text | null |  | Job title shown on screens and reports. |
| `employee_code` | text | null | personal | Employee code from the HR or Apsis record. |
| `phone` | text | null | personal | Mobile phone number of the user. |
| `email` | text | null | personal | E-mail address. |
| `locale` | text | not null |  | Preferred app language: bn or en. |
| `home_zone_id` | bigint | null |  | Id of the zone the user belongs to by default. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `password_hash` | text | null | secret | Argon2id password hash in PHC string form; null means no password is set. |
| `password_changed_at` | timestamp with time zone | null |  | UTC time the password was last changed. |
| `must_change_password` | boolean | not null |  | True when the user must set a new password at the next login. |
| `mfa_enabled` | boolean | not null |  | True when TOTP is confirmed for the user. |
| `pilot` | boolean | not null |  | True when the user is in the pilot group for the cutover. |
| `scope_version` | bigint | not null |  | Counter bumped on any change of role, status, scope or assignment; tokens carrying an older value are refused. |
| `last_login_at` | timestamp with time zone | null |  | UTC time of the most recent successful login. |
| `disabled_at` | timestamp with time zone | null |  | UTC time the account was disabled; null while active. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (home_zone_id) REFERENCES app.zone(id)`; `FOREIGN KEY (role) REFERENCES app.role_def(role)`

## app.archive_manifest

One row per exported month partition: planned, exported, verified, dropped, restored; a partition is dropped only after its row is verified.

`owner: db | capture: SERVER | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `parent` | text | not null |  | Partitioned parent table (schema-qualified). |
| `partition_name` | text | not null |  | Schema-qualified month partition, e.g. app.memo_y2026m10. |
| `month` | date | not null |  | First day of the partition's month. |
| `row_count` | bigint | null |  | Rows exported. |
| `sha256` | text | null |  | SHA-256 of the exported file, lower-case hex. |
| `blob_url` | text | null |  | Blob location of the export (no SAS token). |
| `format` | text | null |  | Export format: parquet or sql_gz. |
| `status` | text | not null |  | planned > exported > verified > dropped > restored; never backwards. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was planned. |
| `exported_at` | timestamp with time zone | null |  | UTC instant the export finished. |
| `verified_at` | timestamp with time zone | null |  | UTC instant the export was read back and matched row count and hash. |
| `dropped_at` | timestamp with time zone | null |  | UTC instant the partition was dropped from the primary. |

Keys: `UNIQUE (partition_name)`; `PRIMARY KEY (id)`

## app.astha_target

Astha programme target for a route (optionally an outlet and brand) and month, in standard units and memo count.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `programme_id` | bigint | null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `month` | date | not null |  | First day of the calendar month. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `brand_id` | bigint | null |  | Product brand (app.product_node of level brand). |
| `std_target` | numeric(16,3) | not null |  | Target in standard units for the route, month and brand, with three decimals. |
| `memo_target` | integer | not null |  | Target number of memos for the route and month. |
| `batch_uuid` | uuid | null |  | UUID of the batch or bulk operation that carried or created the row (idempotency of the batch). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (brand_id) REFERENCES app.product_node(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`

## app.attendance_event

One check-in or check-out of a user with its on-demand location fix.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `address_display` | text | null | personal | Reverse-geocoded address shown for display only; never used for a decision. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.audit_log

Append-only, hash-chained record of web and admin actions with before and after images and the actor.

`owner: backend:platform | capture: SERVER | retention: audit | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `chain_seq` | bigint | not null |  | Position in the hash chain, assigned by the insert trigger; strictly increasing. |
| `at` | timestamp with time zone | not null |  | UTC time the action was recorded, set by the server. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `actor_user_id` | bigint | null |  | Id of the user who acted; null for system actions. |
| `actor_username` | text | null | personal | Username of the actor at the time of the action. |
| `actor_role` | text | null |  | Role of the actor at the time of the action. |
| `via` | text | not null |  | Channel the action came through: device, web, api or job. |
| `entity` | text | not null |  | Type of the thing that was changed, for example outlet or cfg_value. |
| `entity_id` | text | not null |  | Identifier of the changed thing, as text. |
| `action` | text | not null |  | What was done, for example create, update, approve or void. |
| `before` | jsonb | null | personal | JSON image of the entity before the change; may contain personal fields. |
| `after` | jsonb | null | personal | JSON image of the entity after the change; may contain personal fields. |
| `reason` | text | null |  | Reason given for the change or action (free text). |
| `request_id` | uuid | null |  | Request id of the API call that caused the change. |
| `ip_class` | text | null |  | Coarse class of the caller address; the full address is not stored. |
| `prev_hash` | bytea | null |  | row_hash of the previous row in the chain; null for the first row. |
| `row_hash` | bytea | not null |  | Hash over this row's content and prev_hash, used to verify the chain. |

Keys: `UNIQUE (chain_seq)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (actor_role) REFERENCES app.role_def(role)`; `FOREIGN KEY (actor_user_id) REFERENCES app.app_user(id)`

## app.auth_lockout

Failed-login counter and lock state per lock key, shared by every API replica.

`owner: backend:auth | capture: SERVER | retention: session | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `lock_key` | text | not null |  | Key the counter applies to (for example user or device), per the lockout key mode. |
| `failures` | integer | not null |  | Failed attempts counted in the current window. |
| `window_started_at` | timestamp with time zone | not null |  | UTC start of the current failure-counting window. |
| `locked_until` | timestamp with time zone | null |  | UTC time the lock ends; null when not locked. |
| `lock_count` | integer | not null |  | Number of times this key has been locked. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (lock_key)`

## app.bundle_snapshot

One row is a distinct day-bundle content the server generated for a user and business date, numbered in order.

`owner: backend:sync | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_id` | bigint | not null |  | User the bundle was generated for. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the bundle. |
| `snapshot_seq` | integer | not null |  | Order of the content within the user and date, from 1; the <seq> of bundle_version <date>:<seq>. |
| `content_sha256` | bytea | not null |  | SHA-256 of the bundle content; equal to the latest row means the seq is reused. |
| `generated_at` | timestamp with time zone | not null |  | UTC instant the snapshot was first generated. |

Keys: `PRIMARY KEY (user_id, business_date, snapshot_seq)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.calendar_holiday

One declared holiday or selling-day override for a geography scope and date.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `date` | date | not null |  | Dhaka calendar date the declaration applies to. |
| `scope_type` | text | not null |  | Level of the scope: global, role, wing, division, territory, geo_class, zone, route, outlet, user or device. |
| `scope_id` | bigint | not null |  | Id of the scope node; 0 for global; role and geo_class use the app.role_def and app.geo_class_def ordinals. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `selling_day` | boolean | not null |  | True when selling still happens on this date despite the holiday. |
| `name_en` | text | not null |  | Display name in English. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `reason` | text | null |  | Reason given for the change or action (free text). |
| `declared_at` | timestamp with time zone | not null |  | UTC time the holiday was declared. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `revoked_at` | timestamp with time zone | null |  | UTC instant the row was revoked; null while valid. |
| `revoked_by` | bigint | null |  | Id of the user who revoked the declaration; null while in force. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (revoked_by) REFERENCES app.app_user(id)`

## app.call_assessment

One AMO or TSO joint-call assessment or retailer questionnaire for a visit, with total and maximum score.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `visit_client_uuid` | uuid | null |  | client_uuid of the visit the row belongs to. |
| `visit_plan_outlet_client_uuid` | uuid | null |  | Client UUID of the visit-plan outlet this assessment belongs to, when planned. |
| `rubric_id` | bigint | not null |  | Id of the assessment rubric used. |
| `rubric_version` | integer | not null |  | Version of the rubric used. |
| `assessed_user_id` | bigint | null |  | Id of the user being assessed. |
| `total_score` | integer | not null |  | Sum of the criterion scores awarded. |
| `max_score` | integer | not null |  | Maximum score possible for the rubric. |
| `delegate_task` | boolean | not null |  | True when the assessor delegated a follow-up task. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (assessed_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.call_assessment_answer

One criterion answer (score, text or yes/no) of a call assessment.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `assessment_client_uuid` | uuid | not null |  | Client UUID of the call assessment this answer belongs to. |
| `criterion_id` | bigint | not null |  | Id of the rubric criterion answered. |
| `score` | smallint | null |  | Score awarded for the criterion, when it is scored. |
| `answer_text` | text | null | personal | Free-text answer to the criterion. |
| `answer_bool` | boolean | null |  | Yes or no answer to the criterion. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.cfg_ack

Record from a phone saying which config version it applied and which keys changed.

`owner: backend:config | capture: OFFLINE | retention: telemetry | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `acked_config_version` | bigint | not null |  | Config version the phone reports it applied. |
| `applied_at` | timestamp with time zone | not null |  | UTC time the phone applied the config. |
| `keys` | text[] | not null |  | Config keys that changed and were applied. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.cfg_change

One config change request with its items, risk class, approvals and apply time; part of the maker-checker trail.

`owner: backend:config | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `change_id` | bigint | not null |  | Config change set (app.cfg_change) that produced the row. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `items` | jsonb | not null |  | JSON array of the change items (key, scope, new value, old value, effective time). |
| `reason` | text | not null |  | Reason given for the change or action (free text). |
| `risk_class` | smallint | not null |  | Risk class 0 to 3 of the change, after escalation. |
| `requested_by` | bigint | not null |  | Id of the user who requested the change. |
| `requested_at` | timestamp with time zone | not null |  | UTC time the change was requested. |
| `approver` | bigint | null |  | Id of the user who approved the change. |
| `approved_at` | timestamp with time zone | null |  | UTC time the change was approved. |
| `apply_at` | timestamp with time zone | null |  | UTC time the change is scheduled to apply. |
| `decided_at` | timestamp with time zone | null |  | UTC instant of the decision. |
| `decision_note` | text | null |  | Free-text note entered with the decision. |
| `config_version` | bigint | null |  | Global config version in force for the row (at capture for device records). |
| `is_revert_of` | bigint | null |  | Id of the change this one reverts, if any. |
| `blast_radius` | jsonb | not null |  | JSON estimate of the users, routes or devices the change affects. |
| `client_uuid` | uuid | null |  | Client UUID of the create command from the portal, making creation idempotent. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (change_id)`

References: `FOREIGN KEY (approver) REFERENCES app.app_user(id)`; `FOREIGN KEY (requested_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (config_version) REFERENCES app.cfg_version(config_version)`

## app.cfg_key

Registry of every config key with type, default, bounds, scope levels, risk class and delivery; seeded by migration, never deleted.

`owner: backend:config | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `key` | text | not null |  | Dotted config key, for example cfg.geo.radius_m. |
| `area` | text | not null |  | Functional area the key belongs to, such as auth, geo or sync. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `value_type` | text | not null |  | Type of the value: int, number, bool, time, text, url, pct, money_mtk, enum, list or json. |
| `default_value` | jsonb | null |  | Default value as JSON; JSON null means no value. |
| `bounds` | jsonb | not null |  | JSON bounds the value must respect: min, max, enum, max_items, dynamic_min and dynamic_max. |
| `bounds_rule` | text | null |  | Text of a bound the JSON bounds cannot express, such as time ranges; enforced by the config module. |
| `scope_levels` | text[] | not null |  | Levels at which a value may be set, such as global, role, wing or zone. |
| `risk_class` | smallint | not null |  | Lowest risk class 0 to 3 of changing the key. |
| `risk_rule` | text | null |  | Text of the rule that raises the risk class with level or value. |
| `effect` | text | not null |  | When a change takes effect: B at once, S at the next session or day, R with the next release. |
| `delivery` | text | not null |  | Where the key is used: server, device or both. |
| `requires_ack` | boolean | not null |  | True when phones must acknowledge applying a change. |
| `future_dated_only` | boolean | not null |  | True when values must have a future effective date. |
| `restrictive_dir` | text | not null |  | Which direction of change is more restrictive: up, down, enum_order or none. |
| `editor_permission` | text | not null |  | Admin permission needed to edit the key. |
| `description_en` | text | not null |  | English description of the key. |
| `description_bn` | text | null |  | Bangla description of the key. |
| `retired_at` | timestamp with time zone | null |  | UTC time the key was retired; null while in use. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (key)`

## app.cfg_value

One scoped value of a config key valid for a period; rows are closed, never edited.

`owner: backend:config | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `key` | text | not null |  | Config key this value is for. |
| `scope_type` | text | not null |  | Level of the scope: global, role, wing, division, territory, geo_class, zone, route, outlet, user or device. |
| `scope_id` | bigint | not null |  | Id of the scope node; 0 for global; role and geo_class use the app.role_def and app.geo_class_def ordinals. |
| `value` | jsonb | not null |  | The value as JSON, typed by the key's value_type. |
| `effective_from` | timestamp with time zone | not null |  | UTC time the value takes effect. |
| `effective_to` | timestamp with time zone | null |  | UTC time the value stops applying (exclusive); null means open. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `superseded_in_version` | bigint | null |  | Config version in which a newer value replaced this one. |
| `change_id` | bigint | null |  | Config change set (app.cfg_change) that produced the row. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `reason` | text | not null |  | Reason given for the change or action (free text). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (change_id) REFERENCES app.cfg_change(change_id)`; `FOREIGN KEY (config_version) REFERENCES app.cfg_version(config_version)`; `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (key) REFERENCES app.cfg_key(key)`; `FOREIGN KEY (superseded_in_version) REFERENCES app.cfg_version(config_version)`

## app.cfg_version

One committed config version; versions are global and increase monotonically.

`owner: backend:config | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `change_id` | bigint | null |  | Id of the cfg_change that produced the version. |
| `committed_at` | timestamp with time zone | not null |  | UTC instant the row was committed. |
| `committed_by` | bigint | not null |  | Id of the user or system account that committed the version. |
| `summary` | text | not null |  | Short text summarising the changes in the version. |
| `max_risk_class` | smallint | not null |  | Highest risk class among the version's changes. |
| `is_revert_of` | bigint | null |  | Config version this one reverts, if any. |

Keys: `PRIMARY KEY (config_version)`

References: `FOREIGN KEY (change_id) REFERENCES app.cfg_change(change_id)`; `FOREIGN KEY (committed_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (is_revert_of) REFERENCES app.cfg_version(config_version)`

## app.cluster

A named group of outlets within a zone, used to organise routes.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `name` | text | not null |  | Display name. |
| `cluster_type` | text | null |  | Free-text type label of the cluster, up to 60 characters. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (external_ref)`; `UNIQUE (zone_id, name)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.code_list

Header of a business code list (reasons, outcomes, channels and similar) with its key.

`owner: backend:masterdata | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `list_key` | text | not null |  | Key naming the code list, for example force_reason or void_reason. |
| `description` | text | null |  | Free-text description. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (list_key)`

## app.code_list_item

One code of a business code list with English and Bangla labels; codes never change, only retire.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `list_key` | text | not null |  | Key of the code list the item belongs to. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `label_en` | text | not null |  | English label of the code. |
| `label_bn` | text | null |  | Bangla label of the code. |
| `sort` | integer | not null |  | Display order among siblings. |
| `attrs` | jsonb | not null |  | JSON extra attributes of the code, which depend on the list. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First day the row is no longer in effect (exclusive); null means open-ended. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (list_key, code)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (list_key) REFERENCES app.code_list(list_key)`

## app.content_item

An audio-visual or key-visual item played during sales calls, with its asset, size and version.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `title_en` | text | not null |  | English title of the item. |
| `title_bn` | text | null |  | Bangla title of the item. |
| `asset_url` | text | not null |  | URL of the audio-visual or image asset. |
| `sha256` | bytea | not null |  | SHA-256 of the file content. |
| `bytes` | integer | not null |  | Size of the asset in bytes. |
| `duration_s` | integer | null |  | Playing time of the asset in seconds. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | not null |  | First day the row is no longer in effect (exclusive); null means open-ended. |
| `sequence` | smallint | not null |  | Play order of the item among its peers. |
| `outlet_ids` | bigint[] | not null |  | Ids of the outlets the item is limited to; empty means every outlet. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `asset_id` | uuid | null |  | Uploaded admin asset that holds the file (null for items created before V0023). |
| `assigned_scope` | jsonb | not null |  | Geography nodes the item is assigned to, as an array of {scope_type, scope_id}; expanded to outlet_ids on every write. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (asset_id) REFERENCES app.admin_asset(asset_id)`

## app.content_view

One showing or skipping of a content item during a call.

`owner: backend:sync | capture: OFFLINE | retention: telemetry | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `content_id` | bigint | not null |  | Id of the content item shown or skipped. |
| `content_version` | integer | not null |  | Version of the content item at the time. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `outcome` | text | not null |  | Result: viewed, skipped_missing or skipped_user. |
| `sequence_no` | smallint | not null |  | Order of the row within its parent or day. |
| `started_at` | timestamp with time zone | null |  | UTC time playback started on the phone. |
| `duration_ms` | integer | null |  | Milliseconds the item was shown. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (content_id) REFERENCES app.content_item(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.day_exception

A rain, hartal or other day exception raised from the field for routes and dates, decided by the zone TSO.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `reason_code` | text | not null |  | Reason code from the matching business code list (app.code_list_item). |
| `route_ids` | bigint[] | not null |  | Ids of the routes the exception covers. |
| `from_date` | date | not null |  | First Dhaka date the exception covers. |
| `to_date` | date | not null |  | Last Dhaka date the exception covers. |
| `note` | text | null | personal | Free-text note. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `decided_by` | bigint | null |  | User who took the decision. |
| `decided_at` | timestamp with time zone | null |  | UTC instant of the decision. |
| `decision_note` | text | null |  | Free-text note entered with the decision. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (decided_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.db_role_grant

Least-privilege grant map of the database roles; app.apply_db_role_grants() generates every GRANT from it.

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `role` | text | not null |  | User role (contract Role). |
| `schema_name` | text | not null |  | Schema the grant applies to: app or dw. |
| `object` | text | not null |  | Table or view name, or a value starting with '*' for every table of the schema. |
| `privileges` | text | not null |  | Comma-separated privileges granted: SELECT, INSERT, UPDATE, DELETE. |
| `except_tables` | text[] | not null |  | Tables a '*' row leaves out. |
| `except_columns` | text[] | not null |  | Columns a single-table row leaves out; the role is then granted the privilege on every other column. |
| `note` | text | not null |  | Free-text note. |
| `only_columns` | text[] | not null |  | Columns a single-table row grants (column grant); empty grants the whole table. |

Keys: `UNIQUE (role, schema_name, object)`; `PRIMARY KEY (id)`

## app.db_role_limit

Session limits per privilege role (docs/18 s2.6); app.apply_login_limits() writes them onto the login identities.

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `role` | text | not null |  | Privilege role the limits belong to. |
| `statement_timeout` | text | not null |  | statement_timeout for its logins, for example 15s. |
| `lock_timeout` | text | null |  | lock_timeout for its logins; null leaves the server default. |
| `idle_in_transaction_session_timeout` | text | not null |  | idle_in_transaction_session_timeout for its logins. |
| `note` | text | not null |  | Source of the limit. |

Keys: `PRIMARY KEY (role)`

## app.device

One enrolled phone with its status, trust level, key, integrity verdict and last contact.

`owner: backend:auth | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `device_uuid` | uuid | not null |  | UUID the phone generated for itself at enrolment. |
| `flavour` | text | not null |  | Field app flavour: sr, amo or tso. |
| `app_package` | text | not null |  | Android package name of the installed field app. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `status_changed_at` | timestamp with time zone | not null |  | UTC instant of the last status change. |
| `status_reason` | text | null |  | Reason given for the current device status. |
| `device_owner` | boolean | not null |  | True when the Aron app was device owner of the phone at the time. |
| `lockdown_level` | text | not null |  | Device policy lockdown level: dev or prod. |
| `trust_level` | text | not null |  | Trust level of the device: high, normal, low or blocked. |
| `integrity_verdict` | text | not null |  | Latest integrity result: pass, fail, unevaluated or stale. |
| `integrity_checked_at` | timestamp with time zone | null |  | UTC time the integrity verdict was last evaluated. |
| `hardware_backed_key` | boolean | not null |  | True when the device key lives in hardware-backed Keystore. |
| `public_key_jwk` | jsonb | not null |  | Public key of the device as a JSON Web Key; used to verify signed records. |
| `public_key_thumbprint` | text | not null |  | RFC 7638 thumbprint of the device key; one device row per Keystore key. |
| `attestation_summary` | jsonb | not null |  | JSON summary of the key attestation: verified boot, security level, package and certificate digest. |
| `app_signing_cert_sha256` | bytea | not null |  | SHA-256 of the app signing certificate seen on the device. |
| `enrolment_token_id` | bigint | null |  | Id of the enrolment token used to enrol this device. |
| `enrolled_at` | timestamp with time zone | not null |  | UTC time the device enrolled. |
| `device_info` | jsonb | null |  | JSON device information (model, OS version and similar) reported by the phone. |
| `app_version` | text | null |  | App version as versionName+versionCode, e.g. 1.0.3+103. |
| `policy_version_applied` | bigint | null |  | Device policy version the phone last reported applying. |
| `config_version_applied` | bigint | null |  | Config version the phone last reported applying. |
| `last_contact_at` | timestamp with time zone | null |  | UTC time of the last request from the device. |
| `pending_rows_reported` | integer | null |  | Rows waiting to upload that the phone last reported. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `replaced_by_device_id` | bigint | null |  | Id of the device that replaced this one. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `root_hints` | text[] | null |  | Root and tamper hints of the last status report (contract v1.2 DeviceStatusReport.root_hints): NULL = unknown (the phone is older than v1.2 or never reported), empty array = checked and clean. Hints are evidence only, never a reason to block a sale alone. |
| `root_hints_at` | timestamp with time zone | null |  | UTC time of the status report root_hints came from; NULL when root_hints was never reported. |
| `integrity_unavailable_reason` | text | null |  | Reason of the last Play Integrity unavailable marker (contract v1.2 play_integrity_unavailable.reason); NULL = never reported. Kept when a later report has a verdict; compare integrity_unavailable_at with integrity_checked_at for the newer one. |
| `integrity_unavailable_at` | timestamp with time zone | null |  | UTC time of the report that carried the last Play Integrity unavailable marker; NULL = never reported. |

Keys: `UNIQUE (device_uuid)`; `UNIQUE (external_ref)`; `UNIQUE (public_key_thumbprint)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (enrolment_token_id) REFERENCES app.enrolment_token(id)`; `FOREIGN KEY (replaced_by_device_id) REFERENCES app.device(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.device_binding

Link of a user to a phone, with the binding ordinal that fixes the memo-number block.

`owner: backend:auth | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `bind_ordinal` | smallint | not null |  | Binding slot 0 to 3 of the user on the device; selects the memo-number block. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `bound_at` | timestamp with time zone | not null |  | UTC time the user was bound to the device. |
| `bound_via` | text | not null |  | How the binding was made: otp, support or migration. |
| `unbound_at` | timestamp with time zone | null |  | UTC time the binding ended; null while active. |
| `unbound_by` | bigint | null |  | Id of the user who ended the binding. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (unbound_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.device_directive

A signed remote directive sent to a phone, with delivery and acknowledgement times.

`owner: backend:config | capture: ONLINE | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `directive_id` | uuid | not null |  | UUID of the directive, used by the phone to acknowledge it. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `type` | text | not null |  | Directive type: send_status, redownload_bundle, upload_support_bundle or resend_from. |
| `params` | jsonb | not null |  | JSON parameters of the directive. |
| `sig` | text | not null |  | ES256 signature by the device key over the record (header records only). |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |
| `delivered_at` | timestamp with time zone | null |  | UTC time the directive was delivered to the phone. |
| `acked_at` | timestamp with time zone | null |  | UTC time the phone acknowledged the directive. |

Keys: `PRIMARY KEY (directive_id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`

## app.device_nonce

Single-use server nonce for a Play Integrity request or key attestation.

`owner: backend:auth | capture: SERVER | retention: session | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `nonce_sha256` | bytea | not null |  | SHA-256 of the nonce issued to the device. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `purpose` | text | not null |  | Purpose of the fix or photo; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |
| `used_at` | timestamp with time zone | null |  | UTC time the nonce was consumed; null while unused. |

Keys: `PRIMARY KEY (nonce_sha256)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`

## app.device_otp

One-time code a TSO issues to bind a phone to a user, stored as hash and encrypted for display.

`owner: backend:auth | capture: ONLINE | retention: session | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `otp_cipher` | bytea | not null | secret | One-time code encrypted so the TSO can read it while it is unexpired. |
| `otp_sha256` | bytea | not null | secret | SHA-256 of the one-time code, used to verify the entry. |
| `device_model` | text | null |  | Model of the phone the code was issued for, if known. |
| `issued_by` | bigint | null |  | Id of the TSO or support user who issued the code. |
| `reason` | text | null |  | Reason given for the change or action (free text). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |
| `attempts` | integer | not null |  | Wrong attempts made against the code. |
| `consumed_at` | timestamp with time zone | null |  | UTC time the code was used; null while unused. |
| `revoked_at` | timestamp with time zone | null |  | UTC instant the row was revoked; null while valid. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (issued_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.device_policy

The device policy rendered for a phone at a given policy version, with its hash.

`owner: backend:config | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `policy_version` | bigint | not null |  | Policy version, equal to the config version at render time. |
| `lockdown_level` | text | not null |  | Device policy lockdown level: dev or prod. |
| `policy` | jsonb | not null |  | The rendered device policy as JSON. |
| `policy_sha256` | bytea | not null |  | SHA-256 of the rendered policy. |
| `rendered_at` | timestamp with time zone | not null |  | UTC time the policy was rendered. |
| `fetched_at` | timestamp with time zone | null |  | UTC time the phone first downloaded this policy. |
| `applied_at` | timestamp with time zone | null |  | UTC time the phone was first seen applying this version, from its next status report. |

Keys: `PRIMARY KEY (device_id, policy_version)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`

## app.device_status_report

One report of a phone's lockdown, settings, pending rows and battery state.

`owner: backend:auth | capture: OFFLINE | retention: telemetry | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `reported_at` | timestamp with time zone | not null |  | UTC time the phone produced the report. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `config_version` | bigint | null |  | Global config version in force for the row (at capture for device records). |
| `trigger` | text | null |  | Event that caused the report, such as enrolment, check_in or boot. |
| `app_version` | text | not null |  | App version as versionName+versionCode, e.g. 1.0.3+103. |
| `device_owner` | boolean | not null |  | True when the Aron app was device owner of the phone at the time. |
| `lockdown_level_applied` | text | not null |  | Lockdown level the phone enforces: dev or prod. |
| `policy_version_applied` | bigint | null |  | Policy version the phone reports applying. |
| `blocking_active` | boolean | not null |  | True when app blocking is active on the phone. |
| `location_enabled` | boolean | not null |  | True when device location services are on. |
| `dev_options_enabled` | boolean | not null |  | True when Android developer options are on. |
| `adb_enabled` | boolean | not null |  | True when USB debugging is on. |
| `auto_time_enabled` | boolean | not null |  | True when automatic network time is on. |
| `mock_location_apps` | text[] | not null |  | Packages on the phone that hold mock-location permission. |
| `pending_rows` | integer | not null |  | Rows waiting to upload on the phone. |
| `battery_pct` | smallint | not null |  | Battery level in percent at report time. |
| `integrity_verdict` | text | null |  | Integrity result the phone obtained: pass, fail, unevaluated or stale. |
| `report` | jsonb | not null |  | JSON report as received, with the Play Integrity token removed. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.dirty_key

Work queue entry naming a bundle snapshot or aggregate row that the worker must rebuild.

`owner: backend:platform | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `subject_id` | bigint | not null |  | Id of the subject to rebuild; its meaning depends on kind (user, route, zone, outlet or device). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `first_dirtied_at` | timestamp with time zone | not null |  | UTC time the key was first marked dirty. |
| `last_dirtied_at` | timestamp with time zone | not null |  | UTC time the key was last marked dirty. |
| `dirty_count` | integer | not null |  | Number of times the key was marked dirty since it was queued. |
| `reason` | text | null |  | Reason given for the change or action (free text). |
| `claimed_at` | timestamp with time zone | null |  | UTC time a worker claimed the key; null when unclaimed. |
| `claimed_by` | text | null |  | Name of the worker holding the claim. |
| `attempts` | smallint | not null |  | Failed rebuild attempts since the key was last marked dirty. |
| `last_error` | text | null |  | Error text of the last failed rebuild (no personal data). |
| `not_before` | timestamp with time zone | null |  | UTC time before which the worker does not retry the key (back-off); null means at once. |
| `dead_at` | timestamp with time zone | null |  | UTC time the key was parked as dead after too many attempts; null while live. Marking the key again revives it. |

Keys: `PRIMARY KEY (kind, subject_id, business_date)`

## app.distribution_check

One AMO or TSO distribution check of a visited outlet.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `posm_present` | boolean | null |  | True when point-of-sale material was present at the outlet. |
| `note` | text | null |  | Free-text note. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.distribution_check_line

Presence and out-of-stock flags for one brand in a distribution check.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `check_client_uuid` | uuid | not null |  | Client UUID of the distribution check this line belongs to. |
| `brand_id` | bigint | not null |  | Product brand (app.product_node of level brand). |
| `present` | boolean | not null |  | True when the brand was present in the outlet. |
| `oos` | boolean | not null |  | True when the brand was out of stock; implies present. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (brand_id) REFERENCES app.product_node(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.division

Geography level below a wing; groups territories.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `wing_id` | bigint | not null |  | Id of the wing the division belongs to. |
| `email` | text | null |  | Business e-mail of the division office. |
| `address` | text | null |  | Office address of the division. |
| `pda_contact_no` | text | null |  | Business contact number of the office or zone phone. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (wing_id) REFERENCES app.wing(id)`

## app.domain_event

Append-only outbox of events written with each change; the projector and later consumers read it in id order.

`owner: backend:platform | capture: SERVER | retention: ops | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `event_type` | text | not null |  | Type of the event, for example memo.created. |
| `aggregate_type` | text | not null |  | Type of the thing the event is about, for example memo or visit. |
| `aggregate_id` | text | not null |  | Server id or client_uuid of the thing the event is about. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `payload` | jsonb | not null |  | Event payload (JSON object) in the shape of its catalogued version; ids, codes and amounts only, no personal data. |
| `source_client_uuid` | uuid | null |  | Client UUID of the device record that caused the event, if any. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `payload_version` | smallint | null |  | Version of the payload shape, a row of app.domain_event_type with event_type; defaults to 1; null only on rows written before V0017. |
| `tx_id` | xid8 | null |  | Transaction that wrote the row (pg_current_xact_id()); consumers read below app.outbox_horizon() in (tx_id, id) order. Null on rows written before V0033. |

Keys: `PRIMARY KEY (id, business_date)`

## app.domain_event_type

Catalogue of domain-event types and payload versions; every outbox row must name one (docs/data-events.md).

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `event_type` | text | not null |  | Event name, aggregate.verb, for example memo.created. |
| `payload_version` | smallint | not null |  | Version of the payload shape; a breaking change adds a new version row. |
| `aggregate_type` | text | not null |  | aggregate_type the producer writes with this event. |
| `aggregate_id_is` | text | not null |  | Which column aggregate_id holds, for example memo.client_uuid. |
| `producer` | text | not null |  | Backend module that writes the event. |
| `description` | text | not null |  | What happened and what consumers do with it. |
| `payload_schema` | jsonb | not null |  | JSON Schema of the payload (the target shape); its required keys are enforced on insert when enforce_required. |
| `introduced_in` | text | not null |  | Migration that added this version. |
| `deprecated_at` | timestamp with time zone | null |  | UTC instant producers stopped writing this version; null while current. |
| `enforce_required` | boolean | not null |  | True once the producer sends the required keys; the insert trigger then refuses a payload without them. |

Keys: `PRIMARY KEY (event_type, payload_version)`

## app.due_collection

Cash collected against an earlier credit memo, with the collecting location fix.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `against_memo_client_uuid` | uuid | not null |  | Client UUID of the credit memo being paid. |
| `against_memo_no` | text | not null |  | Number of the credit memo being paid. |
| `against_memo_business_date` | date | not null |  | Business date of the credit memo being paid. |
| `amount_mtk` | bigint | not null |  | Amount in milli-taka (1 Tk = 1000 mtk). |
| `is_full_settlement` | boolean | not null |  | True when the collection clears the memo's outstanding due. |
| `outstanding_before_mtk` | bigint | not null |  | Due on the memo before this collection, in milli-taka. |
| `payment_mode` | text | not null |  | How the money was paid; cash only in Phase 1. |
| `visit_client_uuid` | uuid | null |  | client_uuid of the visit the row belongs to. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `zone_id` | bigint | null |  | Zone of the route on the business date, frozen at capture (never rewritten by a later route move). |
| `cluster_id` | bigint | null |  | Cluster of the outlet on the business date, frozen at capture (null without an outlet). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.due_ledger

Append-only ledger of an outlet's outstanding dues: credit memos and opening balances raise it, collections and voids lower it.

`owner: backend:sync | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `entry_kind` | text | not null |  | Kind of entry: memo_due, collection, memo_void, memo_superseded or opening_balance. |
| `amount_mtk` | bigint | not null |  | Amount in milli-taka (1 Tk = 1000 mtk). |
| `memo_client_uuid` | uuid | null |  | client_uuid of the memo the row belongs to. |
| `memo_no` | text | null |  | Printed memo number <username>-<yyMMdd>-<seq>. |
| `source_client_uuid` | uuid | not null |  | Client UUID of the memo, due_collection, memo_void or adjustment that caused the entry. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `note` | text | null |  | Free-text note. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (source_client_uuid, entry_kind)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.enrolment_token

A one-time or limited-use token that lets a phone enrol; only its hash is stored.

`owner: backend:auth | capture: ONLINE | retention: session | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `token_sha256` | bytea | not null |  | SHA-256 of the secret token; the token itself is never stored. |
| `token_prefix` | text | not null |  | First characters of the token, shown so admins can identify it. |
| `flavour` | text | not null |  | Field app flavour: sr, amo or tso. |
| `lockdown_level` | text | not null |  | Device policy lockdown level: dev or prod. |
| `max_uses` | integer | not null |  | Number of devices that may enrol with the token. |
| `used_count` | integer | not null |  | Number of enrolments made with the token. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `release_id` | bigint | null |  | Id of the app release the token is tied to, if any. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |
| `created_by` | bigint | not null |  | User who created the row (null for migrations and jobs). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `revoked_at` | timestamp with time zone | null |  | UTC instant the row was revoked; null while valid. |
| `revoked_by` | bigint | null |  | Id of the user who revoked the token. |
| `note` | text | null |  | Free-text note. |

Keys: `UNIQUE (token_sha256)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (release_id) REFERENCES app.app_release(id)`; `FOREIGN KEY (revoked_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.entry_unlock

One row is a time-limited unlock that lets web entry be back-dated for a zone or route over a date range.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key (unlock_id in the contract). |
| `scope_type` | text | not null |  | What the unlock covers; allowed values are listed under constraints. |
| `scope_id` | bigint | not null |  | Id of the zone or route the unlock covers (by scope_type). |
| `from_date` | date | not null |  | First Asia/Dhaka business date that may be entered. |
| `to_date` | date | not null |  | Last Asia/Dhaka business date that may be entered (at most cfg.web.entry_unlock_max_days after from_date). |
| `reason` | text | not null |  | Reason the granting user gave (10 to 500 characters). |
| `expires_at` | timestamp with time zone | not null |  | UTC time the unlock lapses (creation plus the TTL, cfg.web.entry_unlock_ttl_h by default). |
| `created_by` | bigint | not null |  | User who granted the unlock. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `expired_at` | timestamp with time zone | null |  | UTC time the unlock was expired early (null if it was not); written once. |
| `expired_by` | bigint | null |  | User who expired the unlock early; written once with expired_at. |
| `version` | integer | not null |  | Row version for If-Match; the guard trigger moves it on the expiry. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (expired_by) REFERENCES app.app_user(id)`

## app.event_consumer

Read position of each domain-event consumer.

`owner: backend:platform | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `consumer` | text | not null |  | Name of the consumer, for example the aggregate projector. |
| `last_event_id` | bigint | not null |  | id of the last row the consumer processed; informational for the aggregate projector (it recomputes by dirty key), part of the position for feed consumers. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `last_tx_id` | xid8 | null |  | tx_id of the last row the consumer processed; with last_event_id it is the consumer's position in (tx_id, id) order. Null before the first V0033-era row. |

Keys: `PRIMARY KEY (consumer)`

## app.feedback

Free-text feedback or problem report sent from the TSO app, with optional photo.

`owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `category_code` | text | not null |  | Product category: cigarette, bidi, lighter or match. |
| `title` | text | not null |  | Short title of the feedback. |
| `description` | text | not null | personal | Body of the feedback as written by the TSO. |
| `photo_uuid` | uuid | null |  | Media uuid (app.media) of the photo attached to the row. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.feedback_status

One row is the support-inbox status of a feedback item; a feedback item without a row is new.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `feedback_client_uuid` | uuid | not null |  | client_uuid of the feedback item. |
| `status` | text | not null |  | Inbox status; allowed values are listed under constraints. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `updated_by` | bigint | null |  | User who last set the status. |

Keys: `PRIMARY KEY (feedback_client_uuid)`

References: `FOREIGN KEY (feedback_client_uuid) REFERENCES app.feedback(client_uuid)`; `FOREIGN KEY (updated_by) REFERENCES app.app_user(id)`

## app.final_submit

Final Submit of a zone and business date, made online by the TSO, unless reopened.

`owner: backend:sync | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | replay returns the first success |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `submitted_by` | bigint | not null |  | Id of the user who made the Final Submit. |
| `submitted_at` | timestamp with time zone | not null |  | UTC time of the Final Submit. |
| `via` | text | not null |  | Channel the action came through: device, web, api or job. |
| `route_states` | jsonb | not null |  | JSON of the route states the submitter saw in the preview. |
| `late_rows` | integer | not null |  | Rows that were still outstanding when the zone-day was submitted. |
| `reopened_at` | timestamp with time zone | null |  | UTC time the Final Submit was reopened; null if not. |
| `reopened_by` | bigint | null |  | Id of the user who reopened it. |
| `reopen_reason` | text | null |  | Reason given for reopening. |
| `reopen_client_uuid` | uuid | null |  | Client UUID of the reopen command, for idempotent replay. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (reopen_client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (reopened_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (submitted_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.geo_breadcrumb

Batched low-power location points recorded only while breadcrumbs are enabled by config.

`owner: backend:sync | capture: OFFLINE | retention: fix | pii: personal` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique per business date when set. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |

Keys: `UNIQUE (client_uuid, business_date)`; `UNIQUE (external_ref, business_date)`; `PRIMARY KEY (id, business_date)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.geo_class_def

Lookup giving each geography class an integer ordinal for config scoping.

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `geo_class` | text | not null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `ordinal` | smallint | not null |  | Integer used as scope id for the geography class in config values. |

Keys: `UNIQUE (ordinal)`; `PRIMARY KEY (geo_class)`

## app.geo_fix

One location fix carried by a record, with accuracy, provider and sensor evidence used for the server re-check and risk rules.

`owner: backend:sync | capture: OFFLINE | retention: fix | pii: personal` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `source_type` | text | not null |  | Record type that carried the fix. |
| `source_client_uuid` | uuid | not null |  | client_uuid of the record that produced the row. |
| `slot` | text | not null |  | Which fix of the record this is: fix, or edit_fix on an edited memo. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `purpose` | text | not null |  | Purpose of the fix or photo; allowed values are listed under constraints. |
| `fix_status` | text | not null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `lat` | double precision | null | personal | Latitude of the user's location fix (WGS84 degrees). |
| `lng` | double precision | null | personal | Longitude of the user's location fix (WGS84 degrees). |
| `accuracy_m` | double precision | null | personal | Accuracy radius of the user's fix in metres. |
| `altitude_m` | double precision | null | personal | Altitude in metres above the ellipsoid. |
| `vertical_accuracy_m` | double precision | null | personal | Estimated vertical accuracy in metres. |
| `speed_mps` | double precision | null | personal | Speed in metres per second at the fix. |
| `bearing_deg` | double precision | null | personal | Direction of travel in degrees from north. |
| `provider` | text | not null |  | Android location provider of the fix: fused, gps, network, passive or unknown. |
| `fix_time` | timestamp with time zone | null |  | UTC time reported by the location provider for the fix. |
| `fix_elapsed_realtime_ms` | bigint | null |  | Device elapsed-realtime clock of the fix in milliseconds, used to test the fix's age. |
| `fix_age_ms` | bigint | null |  | Age of the fix in milliseconds when the phone used it. |
| `time_to_fix_ms` | integer | null |  | Milliseconds the phone took to obtain the fix. |
| `request_priority` | text | null |  | Location request priority the phone used: high_accuracy or balanced. |
| `is_mock` | boolean | not null |  | True when the location came from a mock location provider (never geo-valid). |
| `reused` | boolean | not null |  | True when the phone reused a recent fix instead of requesting a new one. |
| `refresh_count` | smallint | null |  | Number of times the fix was refreshed before it was used. |
| `gnss` | jsonb | null |  | JSON GNSS summary as received from the phone. |
| `satellites_visible` | smallint | null |  | Satellites visible to the GNSS receiver at the fix. |
| `satellites_used` | smallint | null |  | Satellites used in the position solution. |
| `cn0_used_mean_dbhz` | real | null |  | Mean carrier-to-noise ratio of used satellites in dB-Hz; helps detect spoofed fixes. |
| `cn0_used_max_dbhz` | real | null |  | Highest carrier-to-noise ratio of used satellites in dB-Hz. |
| `cn0_used_stddev_dbhz` | real | null |  | Standard deviation of carrier-to-noise ratio across used satellites in dB-Hz. |
| `radio` | jsonb | null |  | JSON radio-environment summary, present only when cfg.geo.radio_env_enabled is on. |
| `device_owner` | boolean | not null |  | True when the Aron app was device owner of the phone at the time. |
| `dev_options_enabled` | boolean | not null |  | True when Android developer options were on at the fix. |
| `adb_enabled` | boolean | not null |  | True when USB debugging was on at the fix. |
| `auto_time_enabled` | boolean | not null |  | True when automatic network time was on at the fix. |
| `mock_app_present` | boolean | not null |  | True when an app with mock-location permission was installed. |
| `integrity_ref` | uuid | null |  | UUID of the device integrity check this fix refers to. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |

Keys: `UNIQUE (source_client_uuid, slot, business_date)`; `PRIMARY KEY (id, business_date)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.gift

A gift of a loyalty programme with points cost and tiers.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `programme_id` | bigint | not null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name_en` | text | not null |  | Display name in English. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `points_cost` | integer | null |  | Points needed to redeem the gift; null for Astha gifts, which are chosen rather than redeemed. |
| `tier_codes` | text[] | not null |  | Programme tier codes the gift is available to. |
| `image_url` | text | null |  | URL of the gift image. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (programme_id, code)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`

## app.gift_assignment

An Astha gift chosen for an outlet and quarter, locked by the hand-over photo.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `programme_id` | bigint | not null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `quarter` | text | not null |  | Quarter the gift was chosen for, as text such as 2026-Q4. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `gift_id` | bigint | not null |  | Gift of a programme (app.gift); programmes are deferred (docs/27). |
| `tier_code` | text | null |  | Tier of the outlet when the gift was chosen. |
| `chosen_by_user_id` | bigint | null |  | Id of the TSO who chose the gift. |
| `chosen_at` | timestamp with time zone | null |  | UTC time the gift was chosen. |
| `photo_media_uuid` | uuid | null |  | UUID of the hand-over photo that locked the assignment. |
| `locked_at` | timestamp with time zone | null |  | UTC time the assignment was locked by the hand-over photo. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (programme_id, quarter, outlet_id)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (chosen_by_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (gift_id) REFERENCES app.gift(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`

## app.gift_photo

Hand-over photo record of a gift, one per Astha assignment or redeemed campaign unit.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `programme_kind` | text | not null |  | Programme the gift belongs to: diamond_league, astha, campaign or superstar. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `gift_id` | bigint | not null |  | Gift of a programme (app.gift); programmes are deferred (docs/27). |
| `gift_assignment_id` | bigint | null |  | Id of the Astha gift assignment the photo is for. |
| `redemption_client_uuid` | uuid | null |  | Client UUID of the redemption the photo is for. |
| `unit_no` | smallint | null |  | Sequence number of the gift unit within the redemption. |
| `photo_uuid` | uuid | not null |  | Media uuid (app.media) of the photo attached to the row. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (gift_assignment_id) REFERENCES app.gift_assignment(id)`; `FOREIGN KEY (gift_id) REFERENCES app.gift(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.house

Distribution house that a zone may belong to; it carries no rules in Phase 1.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `territory_id` | bigint | not null |  | Territory (app.territory). |
| `email` | text | null |  | Business e-mail of the distribution house. |
| `address` | text | null |  | Address of the distribution house. |
| `pda_contact_no` | text | null |  | Business contact number of the office or zone phone. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (territory_id) REFERENCES app.territory(id)`

## app.indent_movement

Phase 2 indent-portal stock ledger with the same shape as stock_movement; empty in Phase 1.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `business_date_device` | date | null |  | The phone's own business date, kept when the server re-dated the row to the trusted date. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC instant an admin data void tombstoned the row; null while live. Never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `qty_entered` | integer | not null |  | Quantity as typed by the user, in unit_entered. |
| `unit_entered` | text | not null |  | Unit the user typed the quantity in: stick, piece, dozen or pack. |
| `pack_factor` | integer | not null |  | Base units per pack of the SKU at capture (copied from the SKU). |
| `qty_base` | integer | not null |  | Quantity in the SKU's base unit (sticks, pieces or dozens). |
| `reason_code` | text | null |  | Reason code from the matching business code list (app.code_list_item). |
| `slip_printed` | boolean | not null |  | True when a stock slip was printed for the movement. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`

## app.ingest_registry

Global register of every device record's client_uuid and payload hash; the uniqueness point for idempotent sync.

`owner: backend:sync | capture: SERVER | retention: ops | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `record_type` | text | not null |  | Sync record type (contract RecordType). |
| `payload_sha256` | bytea | not null |  | SHA-256 of the canonical (RFC 8785) JSON form of the record payload. |
| `content_fp` | bytea | null |  | Content fingerprint used to detect a duplicate record under a different client_uuid. |
| `family_uuid` | uuid | null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `outcome_code` | text | null |  | Record outcome code when the record was not accepted. |
| `server_id` | bigint | null |  | Server id of the stored row the record produced, if it was accepted. |
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `first_batch_uuid` | uuid | not null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `last_seen_at` | timestamp with time zone | not null |  | UTC time the same client_uuid was last received. |
| `seen_count` | integer | not null |  | Number of times the client_uuid was received. |
| `flags` | text[] | not null |  | Flags on the accepted record: resync_late = re-sent after a failover or restore and accepted past cfg.sync.max_backdate_days (F-SYS-089); empty when none. |

Keys: `PRIMARY KEY (client_uuid)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.leave_application

A TSO leave application with dates and reason, decided on the web.

`owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `leave_type_code` | text | not null |  | Code of the leave type from the leave_type code list. |
| `from_date` | date | not null |  | First day of leave. |
| `days` | integer | not null |  | Number of leave days requested. |
| `to_date` | date | null |  | Last day of leave. |
| `reason` | text | not null | personal | Reason the TSO gave for the leave (may hold personal circumstances). |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `decided_by` | bigint | null |  | User who took the decision. |
| `decided_at` | timestamp with time zone | null |  | UTC instant of the decision. |
| `decision_note` | text | null | personal | Free-text note entered with the decision. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (decided_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.loyalty_ledger

Append-only points ledger derived on the server, idempotent per source record.

`owner: backend:analytics | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `programme_id` | bigint | not null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `source_type` | text | not null |  | Kind of record that caused the entry, such as memo or redemption. |
| `source_id` | text | not null |  | Identifier of the source record; with source_type it makes the entry idempotent. |
| `points` | integer | not null |  | Points of the entry: positive for earning, negative for a debit or expiry. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `expires_on` | date | null |  | Date the earned points expire; null when they do not expire. |
| `flags` | text[] | not null |  | Text flags set on the entry, for example negative_balance. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (source_type, source_id)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`

## app.media

Metadata of a photo taken in the field with its location fix and Blob Storage path; the image itself is in Blob Storage.

`owner: backend:media | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `purpose` | text | not null |  | Purpose of the fix or photo; allowed values are listed under constraints. |
| `ref_type` | text | not null |  | Type of the record the photo is attached to. |
| `ref_client_uuid` | uuid | not null |  | Client UUID of the record the photo is attached to. |
| `sha256` | bytea | not null |  | SHA-256 of the file content. |
| `phash` | text | null |  | Perceptual hash of the image, used to detect reused photos. |
| `bytes` | integer | not null |  | Compressed image size in bytes as reported by the phone. |
| `width` | integer | not null |  | Image width in pixels. |
| `height` | integer | not null |  | Image height in pixels. |
| `mime` | text | not null |  | Media type of the image, for example image/jpeg. |
| `blob_path` | text | not null |  | Path of the image in Blob Storage. |
| `taken_at` | timestamp with time zone | not null |  | UTC time the photo was taken. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `stored_at` | timestamp with time zone | null |  | UTC time the image arrived in Blob Storage. |
| `blob_bytes` | integer | null |  | Size in bytes of the stored image, checked against the declared size. |
| `blob_sha256` | bytea | null |  | SHA-256 of the stored image, checked against the declared digest. |

Keys: `UNIQUE (blob_path)`; `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.memo

One sales memo header (or zero-sale record) for an outlet visit, with gross, discount, net, paid and due amounts.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `memo_no` | text | not null |  | Printed memo number <username>-<yyMMdd>-<seq>. |
| `memo_kind` | text | not null |  | Memo kind: sale or zero_sale. |
| `committed_at` | timestamp with time zone | not null |  | UTC instant the row was committed. |
| `price_list_date` | date | not null |  | Date of the price list used to price the memo. |
| `price_type` | text | not null |  | Selling price type of the outlet or price row: outlet, cc, distributor (and reporting, nto for prices). |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `offer_discount_mtk` | bigint | not null |  | Offer and free-goods discount in milli-taka (zero until the discount engine exists, docs/27). |
| `drp_discount_mtk` | bigint | not null |  | DRP (empty-pack slide) discount in milli-taka. |
| `qc_deduction_mtk` | bigint | not null |  | QC settlement deducted on the memo, in milli-taka. |
| `round_adj_mtk` | smallint | not null |  | Rounding adjustment applied to the net amount, in milli-taka. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `paid_mtk` | bigint | not null |  | Amount paid in cash at the memo, in milli-taka. |
| `due_mtk` | bigint | not null |  | Amount left on credit (due) in milli-taka. |
| `is_credit` | boolean | not null |  | True when the memo leaves a due to be collected later. |
| `outstanding_before_mtk` | bigint | null |  | Outlet's outstanding due before this memo, in milli-taka. |
| `line_count` | smallint | not null |  | Number of memo lines. |
| `discount_line_count` | smallint | not null |  | Number of discount components on the memo. |
| `qc_line_count` | smallint | not null |  | Number of QC fault lines deducted on the memo. |
| `supersedes_client_uuid` | uuid | null |  | Client UUID of the earlier memo this edited memo replaces. |
| `edit_reason_code` | text | null |  | Code of the reason the memo was edited, from the edit_reason list. |
| `offer_version_ids` | bigint[] | not null |  | Ids of the offer versions applied on the memo. |
| `rounding_mode` | text | not null |  | Rounding rule used for the memo; half_up_paisa only. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `status_changed_at` | timestamp with time zone | null |  | UTC instant of the last status change. |
| `voided_by_client_uuid` | uuid | null |  | Client UUID of the memo_void record that voided the memo. |
| `superseded_by_client_uuid` | uuid | null |  | Client UUID of the edited memo that replaced this one. |
| `server_flags` | text[] | not null |  | JSON flags added by server enrichment, such as a price mismatch. |
| `zone_id` | bigint | null |  | Zone of the route on the business date, frozen at capture (never rewritten by a later route move). |
| `cluster_id` | bigint | null |  | Cluster of the outlet on the business date, frozen at capture (null without an outlet). |
| `outlet_channel` | text | null |  | Outlet channel code at capture (as the outlet master held it at ingest). |
| `outlet_geo_class` | text | null |  | Outlet geo class code at capture (as the outlet master held it at ingest). |

Keys: `UNIQUE (client_uuid, business_date)`; `UNIQUE (external_ref, business_date)`; `PRIMARY KEY (id, business_date)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.memo_discount

One discount component of a memo: offer, DRP or free goods, with SKU, quantity and value.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `memo_client_uuid` | uuid | not null |  | client_uuid of the memo the row belongs to. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `sku_id` | bigint | null |  | SKU (app.sku). |
| `qty_base` | integer | null |  | Quantity in the SKU's base unit (sticks, pieces or dozens). |
| `value_mtk` | bigint | not null |  | Value of the discount component in milli-taka. |
| `offer_id` | bigint | null |  | Offer (app.offer) the row refers to; offers are deferred (docs/27). |
| `offer_version_id` | bigint | null |  | Offer version (app.offer_version) applied. |
| `basis_qty_base` | integer | null |  | For DRP discounts, empties collected in base units. |
| `line_no` | smallint | null |  | Memo line the discount applies to; null for a memo-level discount. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (offer_id) REFERENCES app.offer(id)`; `FOREIGN KEY (offer_version_id) REFERENCES app.offer_version(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.memo_line

One SKU line of a memo with quantity, base price and gross amount.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `memo_client_uuid` | uuid | not null |  | client_uuid of the memo the row belongs to. |
| `line_no` | smallint | not null |  | Position of the line within the memo. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `line_kind` | text | not null |  | Line kind: sale, drp_reward, promo_free or free_sample. |
| `qty_entered` | integer | not null |  | Quantity as typed by the user, in unit_entered. |
| `unit_entered` | text | not null |  | Unit the user typed the quantity in: stick, piece, dozen or pack. |
| `pack_factor` | integer | not null |  | Base units per pack of the SKU at capture (copied from the SKU). |
| `qty_base` | integer | not null |  | Quantity in the SKU's base unit (sticks, pieces or dozens). |
| `price_type` | text | not null |  | Selling price type of the outlet or price row: outlet, cc, distributor (and reporting, nto for prices). |
| `price_valid_from` | date | not null |  | Start date of the price row used for the line. |
| `base_price_mtk` | bigint | not null |  | Base price in milli-taka for price_per_qty base units. |
| `price_per_qty` | integer | not null |  | Number of base units the base price covers. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `offer_id` | bigint | null |  | Offer (app.offer) the row refers to; offers are deferred (docs/27). |

Keys: `UNIQUE (client_uuid, business_date)`; `UNIQUE (external_ref, business_date)`; `PRIMARY KEY (id, business_date)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (offer_id) REFERENCES app.offer(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.memo_void

Void of a memo, recorded with its reason and location fix; the memo turns voided and is never deleted.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `memo_client_uuid` | uuid | not null |  | client_uuid of the memo the row belongs to. |
| `memo_no` | text | not null |  | Printed memo number <username>-<yyMMdd>-<seq>. |
| `reason_code` | text | not null |  | Reason code from the matching business code list (app.code_list_item). |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `retailer_ack` | boolean | not null |  | True when the retailer acknowledged the void. |
| `note` | text | null | personal | Free-text note. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.mfa_secret

A user's TOTP secret (encrypted) and recovery code hashes.

`owner: backend:auth | capture: ONLINE | retention: master | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `secret_cipher` | bytea | not null | secret | TOTP secret, encrypted by the API with a key from Key Vault. |
| `recovery_code_hashes` | text[] | not null | secret | Hashes of the user's one-time recovery codes. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `confirmed_at` | timestamp with time zone | null |  | UTC time the user confirmed the TOTP enrolment. |
| `last_used_step` | bigint | null |  | Last accepted TOTP time step; blocks replay of a code. |

Keys: `PRIMARY KEY (user_id)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.offer

Stable identity of an offer; each rule change is a new offer_version.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `group_code` | text | not null |  | Code of the group the offer belongs to, used to apply stacking rules. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `current_version_id` | bigint | null |  | Id of the offer_version currently in force for the offer. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (current_version_id) REFERENCES app.offer_version(id)`

## app.offer_version

One version of an offer's rule, validity and reward; memos record the versions they used.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `offer_id` | bigint | not null |  | Offer (app.offer) the row refers to; offers are deferred (docs/27). |
| `version_no` | integer | not null |  | Sequence number of the version within its offer. |
| `offer_type` | text | not null |  | Rule type: pct_discount, amount_per_unit, free_qty or drp_slide. |
| `level` | text | not null |  | Whether the offer applies per line or to the whole memo. |
| `title_en` | text | not null |  | English title shown to the rep. |
| `title_bn` | text | null |  | Bangla title shown to the rep. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | not null |  | Last day, inclusive, the version applies; null means open-ended. |
| `threshold_qty_base` | integer | null |  | Quantity in base units that triggers the offer. |
| `discount_bp` | integer | null |  | Discount in basis points (1/100 of a percent) for pct_discount. |
| `discount_mtk_per_unit` | bigint | null |  | Discount in milli-taka per base unit for amount_per_unit. |
| `reward_sku_id` | bigint | null |  | Id of the SKU given free for free_qty and drp_slide offers. |
| `reward_qty_base` | integer | null |  | Quantity of the reward SKU in base units. |
| `stacking` | text | not null |  | Whether the offer is exclusive or can stack with others. |
| `rule` | jsonb | not null |  | JSON of extra typed rule parameters such as slab steps; Phase 2 rule tables replace it. |
| `change_reason` | text | null |  | Reason recorded for creating the version. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `UNIQUE (offer_id, version_no)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (offer_id) REFERENCES app.offer(id)`; `FOREIGN KEY (reward_sku_id) REFERENCES app.sku(id)`

## app.offer_version_product

Product the offer version applies to, at category, brand, variant or SKU level.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `offer_version_id` | bigint | not null |  | Offer version (app.offer_version) applied. |
| `product_level` | text | not null |  | Level of the product node: category, brand, variant or sku. |
| `product_id` | bigint | not null |  | Id of the product node at that level. |

Keys: `PRIMARY KEY (offer_version_id, product_level, product_id)`

References: `FOREIGN KEY (offer_version_id) REFERENCES app.offer_version(id)`

## app.offer_version_scope

Geography or channel node an offer version is limited to; no rows means everywhere.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `offer_version_id` | bigint | not null |  | Offer version (app.offer_version) applied. |
| `node_type` | text | not null |  | Type of the scope node: wing, division, territory, zone, route, channel or sub_channel. |
| `node_id` | bigint | not null |  | Id of the scope node. |

Keys: `PRIMARY KEY (offer_version_id, node_type, node_id)`

References: `FOREIGN KEY (offer_version_id) REFERENCES app.offer_version(id)`

## app.outlet

One retail or wholesale outlet with owner, location, route placement and national id, TIN and trade licence.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: sensitive` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `owner_name` | text | not null | personal | Name of the outlet owner. |
| `contact_number` | text | null | personal | Phone number of the outlet contact. |
| `address` | text | null | personal | Postal or street address as entered. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `cluster_id` | bigint | not null |  | Outlet cluster (market group) inside the zone. |
| `channel` | text | not null |  | Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa. |
| `sub_channel_id` | bigint | null |  | Outlet sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `lat` | double precision | null |  | Confirmed outlet pin latitude (WGS84 degrees); null when the outlet has no usable location. |
| `lng` | double precision | null |  | Confirmed outlet pin longitude (WGS84 degrees). |
| `location_basis` | text | not null |  | Quality of the outlet location: master, provisional, placeholder or none; drives the geofence verdict. |
| `location_confirmed` | boolean | not null |  | True when the outlet's location has been confirmed in the field. |
| `location_accuracy_m` | double precision | null |  | Accuracy in metres of the fix that set the outlet location. |
| `provisional_lat` | double precision | null |  | Latitude in degrees of the provisional pin captured in the field, before confirmation. |
| `provisional_lng` | double precision | null |  | Longitude in degrees of the provisional pin captured in the field, before confirmation. |
| `outlet_kind` | text | not null |  | Outlet kind: retail or wholesale. |
| `price_type` | text | not null |  | Selling price type of the outlet or price row: outlet, cc, distributor (and reporting, nto for prices). |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `visit_sequence` | integer | null |  | Planned position of the outlet in the route's visit order. |
| `merged_into_id` | bigint | null |  | Id of the outlet this one was merged into; null if not merged. |
| `closed_at` | timestamp with time zone | null |  | UTC time the outlet was closed; null while open. |
| `origin_request_uuid` | uuid | null |  | Client UUID of the outlet_change_request that created the outlet. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |
| `nid_enc` | bytea | null | sensitive | National ID of the outlet owner, envelope-encrypted (key_id + nonce + AES-256-GCM ciphertext + tag); never searched, never in bundles or dw. |
| `tin_enc` | bytea | null | sensitive | Tax identification number, envelope-encrypted like nid_enc. |
| `trade_license_enc` | bytea | null | sensitive | Trade licence number, envelope-encrypted like nid_enc. |
| `pii_key_id` | smallint | null |  | app.pii_key that encrypted this row's *_enc values; null when none is set. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (cluster_id) REFERENCES app.cluster(id)`; `FOREIGN KEY (geo_class) REFERENCES app.geo_class_def(geo_class)`; `FOREIGN KEY (merged_into_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (pii_key_id) REFERENCES app.pii_key(key_id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sub_channel_id) REFERENCES app.sub_channel(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.outlet_change_request

A request from the field to add, close, edit, move or relocate an outlet, with proposed data and decision trail.

`owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: sensitive` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `request_type` | text | not null |  | Kind of request: new, close, info, cluster, location or route_add. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `proposed` | jsonb | not null | sensitive | JSON outlet proposal (names, contacts, ids, location) as submitted. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `photo_uuids` | uuid[] | not null |  | UUIDs of the photos attached to the request. |
| `origin_visit_client_uuid` | uuid | null |  | Client UUID of the visit during which the request was raised. |
| `note` | text | null | personal | Free-text note. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `status_changed_at` | timestamp with time zone | null |  | UTC instant of the last status change. |
| `verified_by` | bigint | null |  | Id of the AMO who verified the request. |
| `verified_at` | timestamp with time zone | null |  | UTC time the request was verified. |
| `decided_by` | bigint | null |  | User who took the decision. |
| `decided_at` | timestamp with time zone | null |  | UTC instant of the decision. |
| `decision_reason` | text | null |  | Reason given for approving or rejecting the request. |
| `created_outlet_id` | bigint | null |  | Id of the outlet created when a new-outlet request was approved. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (created_outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (decided_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (verified_by) REFERENCES app.app_user(id)`

## app.outlet_location_history

Append-only history of every map pin an outlet has had and where it came from.

`owner: backend:masterdata | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `lat` | double precision | null |  | Latitude in WGS84 degrees; null when the basis is placeholder or none. |
| `lng` | double precision | null |  | Longitude in WGS84 degrees; null when the basis is placeholder or none. |
| `accuracy_m` | double precision | null |  | Horizontal accuracy radius of the location fix in metres. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `basis` | text | not null |  | Location basis from valid_from on: master, provisional, placeholder (no usable pin) or none (pin cleared). |
| `source_client_uuid` | uuid | null |  | Client UUID of the outlet_change_request or visit that supplied the fix for this pin. |
| `valid_from` | timestamp with time zone | not null |  | First day the row is in effect. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`

## app.outlet_placement_history

History of an outlet's route and cluster placement over time.

`owner: backend:masterdata | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `cluster_id` | bigint | null |  | Outlet cluster (market group) inside the zone. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the placement no longer applies; null means open. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (cluster_id) REFERENCES app.cluster(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`

## app.outlet_programme

Programme eligibility of an outlet, shown as dots in the SR app outlet list.

`owner: backend:analytics | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `programme_code` | text | not null |  | Code of the programme the outlet is eligible for. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First day the row is no longer in effect (exclusive); null means open-ended. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`

## app.outlet_request_event

Trail of create, verify, approve, reject and lapse events on an outlet change request.

`owner: backend:masterdata | capture: SERVER | retention: audit | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `family_uuid` | uuid | null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `request_uuid` | uuid | not null |  | Client UUID of the outlet_change_request the event belongs to. |
| `event` | text | not null |  | Event: created, verified, discarded, approved, rejected or lapsed. |
| `actor_user_id` | bigint | null |  | Id of the user who caused the event; null for job events. |
| `via` | text | not null |  | Channel the action came through: device, web, api or job. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `business_date_device` | date | null |  | The phone's own business date, kept when the server re-dated the row to the trusted date. |
| `at` | timestamp with time zone | not null |  | UTC time of the event; the capture time for device events. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | null |  | Version of the record payload schema. |
| `device_id` | bigint | null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `config_version` | bigint | null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `sub_channel_id` | bigint | null |  | Outlet sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `photo_uuid` | uuid | null |  | Media uuid (app.media) of the photo attached to the row. |
| `note` | text | null |  | Free-text note. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC instant an admin data void tombstoned the row; null while live. Never deleted. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (actor_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (geo_class) REFERENCES app.geo_class_def(geo_class)`; `FOREIGN KEY (sub_channel_id) REFERENCES app.sub_channel(id)`

## app.partition_policy

List of range-partitioned parent tables with the key column and months to create ahead.

`owner: db | capture: REFERENCE | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `parent` | text | not null |  | Schema-qualified name of the range-partitioned parent table. |
| `key_column` | text | not null |  | Name of the date column the table is partitioned on. |
| `ahead_months` | integer | not null |  | How many months of partitions the worker keeps created ahead. |
| `last_error` | text | null |  | Error text of the last failed partition run for this parent; the worker raises an alert. |
| `last_error_at` | timestamp with time zone | null |  | UTC time of the last failed partition run for this parent. |
| `retention_class` | text | not null |  | Retention class of the parent (app.retention_policy); decides when its month partitions are archived. |

Keys: `PRIMARY KEY (parent)`

References: `FOREIGN KEY (retention_class) REFERENCES app.retention_policy(retention_class)`

## app.password_history

One row is a password hash a user replaced, kept to refuse re-use of the last cfg.auth.password_history_depth passwords.

`owner: backend:auth | capture: ONLINE | retention: master | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `user_id` | bigint | not null |  | User whose password was replaced. |
| `password_hash` | text | not null | secret | Argon2id PHC string of the replaced password (never the password itself). |
| `changed_at` | timestamp with time zone | not null |  | UTC time the password was replaced. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.pii_key

One data-encryption key for outlet NID, TIN and trade licence, stored only wrapped by a Key Vault key (envelope encryption, D-107); never deleted.

`owner: backend:masterdata | capture: SERVER | retention: master | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `key_id` | smallint | not null |  | Key number; the first two bytes of every ciphertext name it. |
| `wrapped_dek` | bytea | not null | secret | AES-256 data-encryption key wrapped (encrypted) by the Key Vault key; never stored unwrapped. |
| `kv_key_name` | text | not null |  | Name of the Key Vault key that wraps the DEK. |
| `kv_key_version` | text | not null |  | Key Vault key version used for the current wrap; changes when a rotation re-wraps. |
| `algorithm` | text | not null |  | Cipher of the data key: AES-256-GCM. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the key was created. |
| `rewrapped_at` | timestamp with time zone | null |  | UTC instant of the last re-wrap by a Key Vault rotation; null if never re-wrapped. |
| `retired_at` | timestamp with time zone | null |  | UTC instant the key stopped encrypting new values; it still decrypts older ciphertext. Null = the active key. |

Keys: `PRIMARY KEY (key_id)`

## app.pii_read_budget

One row is the number of personal-data rows a user has read in one clock hour (hourly PII read budget).

`owner: backend:analytics | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_id` | bigint | not null |  | User whose reads are counted. |
| `hour_start` | timestamp with time zone | not null |  | Start of the UTC clock hour counted (truncated to the hour). |
| `rows_read` | integer | not null |  | Rows with unmasked personal columns read in that hour. |

Keys: `PRIMARY KEY (user_id, hour_start)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.price_batch

One row is a batch of price changes through preview, approval and publish (maker-checker above the change threshold).

`owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `batch_uuid` | uuid | not null |  | Client-generated UUID of the batch; preview, publish and decision are idempotent by it. |
| `status` | text | not null |  | Batch state; allowed values and moves are listed under constraints and the status-flow trigger. |
| `valid_from` | date | not null |  | First Asia/Dhaka business date the new prices are in force. |
| `fingerprint` | text | not null |  | Lower-case hex SHA-256 of the canonical price rows, so a publish matches what was previewed. |
| `price_rows` | jsonb | not null |  | The price rows of the batch (sku_id, price_type, amount_mtk in integer milli-taka, per_base_qty). |
| `row_count` | integer | not null |  | Number of price rows in the batch (1 to 1000). |
| `change_reason` | text | null |  | Reason the maker gave for the change. |
| `backdate` | boolean | not null |  | True when valid_from is before the business date of the publish. |
| `max_change_pct` | numeric(12,2) | null |  | Largest per-row price move in percent, two decimals; display only, the threshold decision is exact integer arithmetic. |
| `previewed_by` | bigint | null |  | User who previewed the batch. |
| `submitted_by` | bigint | null |  | User who submitted the batch for publish (the maker). |
| `submitted_at` | timestamp with time zone | null |  | UTC time of the submission. |
| `decided_by` | bigint | null |  | User who approved or rejected the batch (the checker; never the maker). |
| `decided_at` | timestamp with time zone | null |  | UTC time of the decision. |
| `decision_note` | text | null |  | Note the checker gave with the decision. |
| `price_list_version` | bigint | null |  | Price-list version the publish produced (null until published). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (batch_uuid)`

References: `FOREIGN KEY (decided_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (previewed_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (submitted_by) REFERENCES app.app_user(id)`

## app.price_compliance_check

AMO check comparing the observed retail price of a SKU with the reference price.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `price_type` | text | not null |  | Selling price type of the outlet or price row: outlet, cc, distributor (and reporting, nto for prices). |
| `reference_price_mtk` | bigint | not null |  | Reference retail price in milli-taka. |
| `observed_price_mtk` | bigint | not null |  | Retail price the AMO observed in the outlet, in milli-taka. |
| `compliant` | boolean | not null |  | True when the observed price is within the allowed tolerance of the reference. |
| `note` | text | null |  | Free-text note. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.print_event

One print attempt of a memo, reprint, stock slip, day summary, void slip or due receipt.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `document_kind` | text | not null |  | Printed document: memo, memo_reprint, stock_slip, day_summary, void_slip or due_receipt. |
| `memo_client_uuid` | uuid | null |  | client_uuid of the memo the row belongs to. |
| `ref_client_uuid` | uuid | null |  | Client UUID of the record that was printed. |
| `print_count` | integer | not null |  | Number of copies printed so far for the document. |
| `outcome` | text | not null |  | Result: printed, failed or failed_user. |
| `user_confirmed` | boolean | null |  | True when the user confirmed the print succeeded. |
| `template_version` | integer | not null |  | Version of the print template used. |
| `printer_model` | text | null |  | Model name of the Bluetooth printer. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.print_template

One row is an immutable version of a thermal-print template for one slip kind, in force from effective_from.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `kind` | text | not null |  | Slip the template prints; allowed values are listed under constraints. |
| `version` | integer | not null |  | Template version per kind (cfg.print.template_version selects it). |
| `font_columns` | integer | not null |  | Characters per line of the 58 mm printer font (32 or 42). |
| `template_json` | text | not null |  | Template definition as JSON text (at most 20000 characters). |
| `effective_from` | date | not null |  | First Asia/Dhaka business date the version is in force. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the version. |

Keys: `UNIQUE (kind, version)`; `PRIMARY KEY (id)`

## app.product_node

One node of the product tree (category, segment, brand or variant).

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `level` | text | not null |  | Level in the product tree: category, segment, brand or variant. |
| `parent_id` | bigint | null |  | Id of the parent node; null for a category. |
| `code` | text | null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `sort` | integer | not null |  | Display order among siblings. |
| `sales_enable` | boolean | not null |  | True when the node's SKUs may be sold. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (parent_id) REFERENCES app.product_node(id)`

## app.programme

A loyalty or incentive programme (Diamond League, Astha, campaign, Superstar) with period and rules.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name_en` | text | not null |  | Display name in English. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `period_label` | text | null |  | Label of the programme period, such as 2026-10 for a month or 2026-Q4 for a quarter. |
| `active_from` | date | not null |  | First Dhaka date the programme is active. |
| `active_to` | date | not null |  | Last Dhaka date the programme is active. |
| `points_expire_on` | date | null |  | Date earned points expire; null when they do not expire. |
| `rules` | jsonb | not null |  | JSON rules of the programme. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

## app.programme_enrolment

Enrolment of an outlet in a programme for a period, with league, tier and base target.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `programme_id` | bigint | not null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `league_label` | text | null |  | League name of the outlet in the programme period. |
| `tier_code` | text | null |  | Tier code of the outlet in the programme. |
| `base_target` | numeric(16,3) | null |  | Base target of the outlet for the period, in standard units with three decimals. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the enrolment no longer applies; null means open. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`

## app.push_token

FCM push token of a user on a phone.

`owner: backend:notify | capture: ONLINE | retention: ops | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `provider` | text | not null |  | Android location provider of the fix: fused, gps, network, passive or unknown. |
| `app_flavour` | text | not null |  | App the token belongs to: sr, amo or tso. |
| `token` | text | not null | secret | FCM registration token of the phone. |
| `token_sha256` | bytea | not null |  | SHA-256 of the secret token; the token itself is never stored. |
| `registered_at` | timestamp with time zone | not null |  | UTC time the token was registered. |
| `last_seen_at` | timestamp with time zone | not null |  | UTC time the phone last confirmed the token. |
| `revoked_at` | timestamp with time zone | null |  | UTC instant the row was revoked; null while valid. |
| `revoke_reason` | text | null |  | Reason the token was revoked. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.qc_entry

QC header created by the server for a visit from its QC fault lines.

`owner: backend:sync | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (visit_client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.qc_entry_line

One QC fault line (faulty units) of a visit, optionally deducted on the memo.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `qc_entry_id` | bigint | not null |  | Id of the app.qc_entry header of the visit. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `memo_client_uuid` | uuid | null |  | client_uuid of the memo the row belongs to. |
| `applied_to_memo` | boolean | not null |  | True when the line is deducted on the memo. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `fault_type_code` | text | not null |  | Code of the fault type from the code list. |
| `fault_group` | text | not null |  | Fault group: MFC (manufacturing) or MKT (market). |
| `qty_base` | integer | not null |  | Quantity in the SKU's base unit (sticks, pieces or dozens). |
| `unit_price_mtk` | bigint | not null |  | Price in milli-taka per unit used to value the fault. |
| `settlement_mtk` | bigint | not null |  | Amount in milli-taka settled for the faulty units. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (qc_entry_id) REFERENCES app.qc_entry(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.qc_summary_entry

One row is a back-office QC summary entered on the web: market QC for a route or warehouse QC for a zone.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Browser-generated UUID of the save; the API is idempotent by it. |
| `qc_source` | text | not null |  | Where the QC was done; allowed values are listed under constraints (source in the contract). |
| `zone_id` | bigint | not null |  | Zone of the QC. |
| `route_id` | bigint | null |  | Route of a market QC (required for market, null for warehouse). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the QC. |
| `reason` | text | null |  | Reason given (required for warehouse QC). |
| `source` | text | not null |  | Origin of the entry; always web. |
| `entered_by` | bigint | not null |  | User who saved the entry. |
| `entered_at` | timestamp with time zone | not null |  | UTC instant of the save. |
| `voided_at` | timestamp with time zone | null |  | UTC time a data void voided the entry (tombstone); written once. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (entered_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.qc_summary_entry_line

One row is a faulty quantity of one SKU and fault type in a web QC summary.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `entry_client_uuid` | uuid | not null |  | client_uuid of the QC summary the row belongs to. |
| `sku_id` | bigint | not null |  | SKU of the row. |
| `fault_type_code` | text | not null |  | Fault type code (code list of QC fault types). |
| `qty_base` | bigint | not null |  | Faulty quantity in the SKU's base unit (sticks, pieces or dozens). |

Keys: `UNIQUE (entry_client_uuid, sku_id, fault_type_code)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (entry_client_uuid) REFERENCES app.qc_summary_entry(client_uuid)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`

## app.redemption

A loyalty redemption basket confirmed in the field; the server debits the loyalty ledger.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `programme_id` | bigint | not null |  | Programme (app.programme); programmes are deferred (docs/27). |
| `visit_client_uuid` | uuid | null |  | client_uuid of the visit the row belongs to. |
| `balance_before_points` | integer | not null |  | Points balance of the outlet before the redemption. |
| `points_total` | integer | not null |  | Total points redeemed in the basket. |
| `cash_points` | integer | not null |  | Points converted to cash in the basket. |
| `cash_mtk` | bigint | not null |  | Cash value in milli-taka paid for the cash points. |
| `cash_rate_mtk_per_point` | bigint | not null |  | Milli-taka paid per point converted to cash. |
| `line_count` | smallint | not null |  | Number of memo lines. |
| `confirmed_at` | timestamp with time zone | not null |  | UTC time the redemption was confirmed on the phone. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `server_flags` | text[] | not null |  | JSON flags added by the server, such as negative_balance (see cfg.loyalty.negative_balance_policy). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (programme_id) REFERENCES app.programme(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.redemption_line

One gift of a redemption basket with quantity and points.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `redemption_client_uuid` | uuid | not null |  | Client UUID of the redemption this line belongs to. |
| `gift_id` | bigint | not null |  | Gift of a programme (app.gift); programmes are deferred (docs/27). |
| `qty` | smallint | not null |  | Number of units of the gift. |
| `points_each` | integer | not null |  | Points cost of one unit of the gift. |
| `points_total` | integer | not null |  | Points for the line: qty times points_each. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (gift_id) REFERENCES app.gift(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.refresh_family

A login session: the family of refresh tokens issued from one login, with expiry and revoke state.

`owner: backend:auth | capture: ONLINE | retention: session | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Id of the device the session belongs to; null for web sessions. |
| `client` | text | not null |  | Client the session belongs to: app_sr, app_amo, app_tso or web. |
| `grant_kind` | text | not null |  | Grant kind: full or upload (upload-only). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `last_used_at` | timestamp with time zone | null |  | UTC time a token of the family was last used. |
| `sliding_expires_at` | timestamp with time zone | not null |  | UTC time the session expires if unused. |
| `absolute_expires_at` | timestamp with time zone | not null |  | UTC time the session expires regardless of use. |
| `revoked_at` | timestamp with time zone | null |  | UTC instant the row was revoked; null while valid. |
| `revoke_reason` | text | null |  | Reason the family was revoked. |
| `device_uuid` | uuid | null |  | UUID of the phone the session belongs to; null for web sessions. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.refresh_token

One refresh token of a family, stored only as a hash, with rotation links.

`owner: backend:auth | capture: ONLINE | retention: session | pii: secret` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `family_id` | bigint | not null |  | Id of the refresh family the token belongs to. |
| `token_sha256` | bytea | not null | secret | SHA-256 of the refresh token; the token itself is never stored. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |
| `used_at` | timestamp with time zone | null |  | UTC time the token was exchanged; reuse signals theft. |
| `replaced_by_id` | bigint | null |  | Id of the token issued in exchange for this one. |

Keys: `UNIQUE (token_sha256)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (family_id) REFERENCES app.refresh_family(id)`; `FOREIGN KEY (replaced_by_id) REFERENCES app.refresh_token(id)`

## app.report_export

One row is a report export (xlsx, pdf or print), synchronous or a queued job: who ran which report with which filters, how many rows and whether personal data was included.

`owner: backend:analytics | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `export_id` | uuid | not null |  | Client- or server-generated UUID of the export; the API is idempotent by it. |
| `report_key` | text | not null |  | Key of the report in the report registry. |
| `user_id` | bigint | not null |  | User who requested the export, from the token. |
| `format` | text | not null |  | Output format; allowed values are listed under constraints. |
| `status` | text | not null |  | Job state; queued, running, done or failed; moves only forward (a lapsed lease may re-queue). |
| `filters` | jsonb | not null |  | The report query as run (scalar filters only; scope comes from the token, never from the client). |
| `scope_hash` | text | not null |  | Hash of the caller's reach at request time, so a later change of scope is visible. |
| `row_count` | integer | null |  | Number of data rows in the export; set when done. |
| `pii_included` | boolean | not null |  | True when the export contains personal columns (unmasked). |
| `blob_path` | text | null |  | Path of the finished file in the export Blob container. |
| `error` | text | null |  | Short error text of a failed export (no personal data). |
| `claimed_by` | text | null |  | Worker instance holding the job lease. |
| `claimed_at` | timestamp with time zone | null |  | UTC time the lease was taken. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `started_at` | timestamp with time zone | null |  | UTC time the export started running. |
| `finished_at` | timestamp with time zone | null |  | UTC time the export finished (done or failed). |
| `expires_at` | timestamp with time zone | null |  | UTC time the download link and the file expire. |

Keys: `PRIMARY KEY (export_id)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.retention_policy

One row per retention class: months a partition stays in the primary database and months its export is kept (docs/16 s13.1, D-371).

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `retention_class` | text | not null |  | Class name: transaction, fix, telemetry, quarantine, audit or event_fact. |
| `hot_months` | integer | null |  | Months a month partition stays in the primary database; null = never leaves it. |
| `keep_months` | integer | null |  | Months the exported partition is kept in the archive; null = for ever. |
| `note` | text | not null |  | Why the window is what it is. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last change. |

Keys: `PRIMARY KEY (retention_class)`

## app.risk_signal

A risk signal (for example mock location or teleport) computed by the worker for a subject and business date.

`owner: backend:analytics | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `severity` | smallint | not null |  | Severity of the signal on a small integer scale. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `subject_type` | text | not null |  | What the signal is about: user, device, visit, outlet, route or memo. |
| `subject_id` | text | not null |  | Identifier of the subject, as text. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `score` | numeric(6,2) | not null |  | Weighted score of the signal. |
| `evidence` | jsonb | not null |  | JSON evidence the rule used to raise the signal. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `UNIQUE (code, subject_type, subject_id, business_date)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.risk_signal_review

Review action on a risk signal by an AMO or web user; append-only.

`owner: backend:analytics | capture: OFFLINE | retention: audit | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `signal_id` | bigint | not null |  | Id of the risk signal reviewed. |
| `action` | text | not null |  | Review action: reviewed, dismissed or confirmed. |
| `note` | text | null | personal | Free-text note. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (signal_id) REFERENCES app.risk_signal(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.role_def

Lookup giving each role an integer ordinal for config scoping.

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `role` | text | not null |  | User role (contract Role). |
| `ordinal` | smallint | not null |  | Integer used as scope id for the role in config values. |
| `field_role` | boolean | not null |  | Whether the role is a field role (SR, AMO or TSO) whose records arrive through sync. |

Keys: `UNIQUE (ordinal)`; `PRIMARY KEY (role)`

## app.role_grant_map

Admin permission granted to each role, carried as the web permission claim.

`owner: backend:auth | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `role` | text | not null |  | User role (contract Role). |
| `permission` | text | not null |  | Admin permission granted to the role. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `PRIMARY KEY (role, permission)`

References: `FOREIGN KEY (role) REFERENCES app.role_def(role)`

## app.route

A sales route in a zone with its code, kind, visit days and label.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `display_label` | text | null |  | Text printed for the route; never used as a key. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `visit_kind` | text | null |  | Visit pattern of the route: daily, 3f (three days a week) or 2f (two days). |
| `visit_days_mask` | smallint | not null |  | Bit mask of visit days: bit0 Saturday through bit6 Friday; 127 means daily. |
| `sequence_no` | integer | null |  | Order of the row within its parent or day. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.route_assignment

Assignment of a user to a route as primary or cover for a date range.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the assignment no longer applies; null means open. |
| `reason` | text | null |  | Reason given for the change or action (free text). |
| `client_uuid` | uuid | null |  | Client UUID of the idempotent online command (such as cover) that created the assignment. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `ended_at` | timestamp with time zone | null |  | UTC instant the activity ended. |
| `ended_by` | bigint | null |  | Id of the user who ended the assignment early. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.route_day

One row per route and business date holding the day state and submit timestamps; created by the server from assignments.

`owner: backend:sync | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `planned` | boolean | not null |  | True when the route or outlet was planned for the business date. |
| `planned_source` | text | not null |  | Why the route is planned: schedule, override or cover. |
| `assigned_user_id` | bigint | null |  | Id of the user assigned to the route that day. |
| `acting_user_id` | bigint | null |  | Id of the user actually working the route, for cover. |
| `state` | text | not null |  | Day state: not_started, logged_in, in_field, synced, submit_pending_rows, sales_submitted or final_submitted. |
| `target_outlets` | integer | null |  | Number of target outlets, frozen at the first bundle of the day. |
| `target_frozen_at` | timestamp with time zone | null |  | UTC time target_outlets was frozen. |
| `route_snapshot_version` | integer | not null |  | Version of the route snapshot the day is based on. |
| `logged_in_at` | timestamp with time zone | null |  | UTC time of the first day_open. |
| `in_field_at` | timestamp with time zone | null |  | UTC time of the first visit. |
| `last_batch_at` | timestamp with time zone | null |  | UTC time of the last upload batch for the route-day. |
| `synced_at` | timestamp with time zone | null |  | UTC time the route-day last became fully synced. |
| `submit_received_at` | timestamp with time zone | null |  | UTC time the server received the latest day_submit. |
| `sales_submitted_at` | timestamp with time zone | null |  | UTC instant Sales Submit took effect. |
| `final_submitted_at` | timestamp with time zone | null |  | UTC time of the Final Submit covering the route-day. |
| `submit_cycle` | integer | not null |  | Sales Submit cycle: 1 for the first submit, plus one after each submit void. |
| `submit_voided` | boolean | not null |  | True when the latest submit was voided. |
| `submit_count_mismatch` | boolean | null |  | True when the submit counts differ from the stored rows. |
| `rows_awaited` | integer | null |  | Rows the phone said were still to come at submit. |
| `settle_deadline_at` | timestamp with time zone | null |  | UTC time by which awaited rows must arrive. |
| `exception_reason` | text | null |  | Reason of the day exception that made the route-day planned or exempt. |
| `late_rows_after_final` | integer | not null |  | Rows that arrived after the Final Submit. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (route_id, business_date)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (assigned_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`

## app.route_day_event

A day_open or day_submit record from a phone or the online Sales Submit.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `route_ids` | bigint[] | null |  | For day_open, ids of the routes opened. |
| `online` | boolean | null |  | True when the phone was online at day_open. |
| `bundle_valid_for` | date | null |  | Business date the downloaded bundle was valid for. |
| `offline_start` | boolean | null |  | True when the day started from a bundle without connectivity. |
| `scope` | text | null |  | For day_submit, whether it covers a route_day or a supervisor_day. |
| `submit_cycle` | integer | null |  | Sales Submit cycle: 1 for the first submit, plus one after each submit void. |
| `device_counts` | jsonb | null |  | JSON counts of records per type as held by the phone at submit. |
| `device_money` | jsonb | null |  | JSON money totals as computed by the phone at submit. |
| `rejected_count` | integer | null |  | Rows rejected so far, as reported by the phone at submit. |
| `quarantined_count` | integer | null |  | Rows quarantined so far, as reported by the phone at submit. |
| `pending_count` | integer | null |  | Rows not yet uploaded, as reported by the phone at submit. |
| `submitted_with_dues` | boolean | null |  | True when the day was submitted with outstanding dues. |
| `dues_outstanding_mtk` | bigint | null |  | Dues outstanding at submit, in milli-taka. |
| `retailers_with_dues` | integer | null |  | Number of retailers with dues at submit. |
| `stock_slip_printed` | boolean | null |  | True when the day's stock slip was printed. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.route_day_void_barrier

Admin data void of a route-day: rows captured before the barrier are voided, later rows accepted.

`owner: backend:sync | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `barrier_at` | timestamp with time zone | not null |  | UTC time before which captured rows are voided. |
| `voided_by` | bigint | not null |  | Id of the admin who ran the data void. |
| `approved_by` | bigint | null |  | Id of the second admin who approved the data void. |
| `reason` | text | not null |  | Reason given for the change or action (free text). |
| `affected` | jsonb | not null |  | JSON count of rows voided per record type. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `UNIQUE (client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (approved_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (voided_by) REFERENCES app.app_user(id)`

## app.route_planned

Planned visit days of a route, effective-dated.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `visit_kind` | text | null |  | Visit pattern in force for the period: daily, 3f or 2f. |
| `visit_days_mask` | smallint | not null |  | Bit mask of planned visit days: bit0 Saturday through bit6 Friday. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the planned days no longer apply; null means open. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (route_id) REFERENCES app.route(id)`

## app.route_zone_history

One row is a period in which a route belonged to a zone; written by triggers on app.route.

`owner: db | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `route_id` | bigint | not null |  | Route. |
| `zone_id` | bigint | not null |  | Zone the route belonged to in the period. |
| `valid_from` | date | null |  | First Asia/Dhaka business date of the period (null = since the route existed). |
| `valid_to` | date | null |  | Asia/Dhaka business date the period ended, exclusive (null = current). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.rubric

One row is a scoring rubric (joint call or retailer questionnaire); its current version is in rubric_version.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `version` | integer | not null |  | Current published version (rubric_version.version); also the ETag. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `PRIMARY KEY (id)`

## app.rubric_version

One row is an immutable published version of a rubric: its scored criteria; assessments reference it.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `rubric_id` | bigint | not null |  | Rubric the version belongs to. |
| `version` | integer | not null |  | Version number, from 1 upwards per rubric. |
| `criteria` | jsonb | not null |  | Array of criteria (id, English and Bangla text, maximum score). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who published the version. |

Keys: `PRIMARY KEY (rubric_id, version)`

References: `FOREIGN KEY (rubric_id) REFERENCES app.rubric(id)`

## app.sale_abort

A memo number consumed without a memo, explaining gaps in memo numbering.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `memo_no` | text | not null |  | Printed memo number <username>-<yyMMdd>-<seq>. |
| `reason` | text | not null |  | Reason given for the change or action (free text). |
| `visit_client_uuid` | uuid | null |  | client_uuid of the visit the row belongs to. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.sales_plan

SKU enabled for a zone for a date range.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the SKU is no longer enabled for the zone; null means open. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (zone_id) REFERENCES app.zone(id)`

## app.security_event

Append-only security events (login failures, lockouts, refresh reuse, device proof and state refusals, scope and password changes, force logout, OTP views) for alerts and support (docs/21 s8.1).

`owner: backend:platform | capture: SERVER | retention: audit | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `at` | timestamp with time zone | not null |  | UTC instant of the event. |
| `kind` | text | not null |  | login_failure, lockout, refresh_reuse, device_proof_invalid, device_state_refused, scope_changed, password_change, force_logout or otp_view. |
| `user_id` | bigint | null |  | User the event names; no foreign key (a failure may name an unknown account); null when none. |
| `device_uuid` | uuid | null |  | Device the event came from; null when unknown. |
| `request_id` | uuid | null |  | Request id of the API call, to join the structured log line. |
| `detail` | jsonb | not null | personal | Short facts (route, code, username_hash, ip_class, failures, family); never a password, token or OTP. |

Keys: `PRIMARY KEY (id)`

## app.server_generation

Database lineage: one row per new generation after creation, failover or restore.

`owner: backend:sync | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `generation` | uuid | not null |  | UUID identifying the database generation. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `started_at` | timestamp with time zone | not null |  | UTC time the generation started. |
| `restore_point_utc` | timestamp with time zone | null |  | UTC time restored to, for a point-in-time restore. |
| `lost_after_utc` | timestamp with time zone | null |  | UTC time after which acknowledged writes may have been lost. |
| `is_current` | boolean | not null |  | True for the one current generation. |
| `note` | text | null |  | Free-text note. |

Keys: `PRIMARY KEY (generation)`

## app.sku

A sellable product with unit, pack size, names and category.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `variant_id` | bigint | not null |  | Id of the variant node the SKU belongs to. |
| `category_code` | text | not null |  | Product category: cigarette, bidi, lighter or match. |
| `name` | text | not null |  | Display name. |
| `short_name` | text | not null |  | Short product name printed on the 58 mm memo. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `base_unit` | text | not null |  | Unit in which quantities are counted: stick, piece or dozen. |
| `base_per_pack` | integer | not null |  | Number of base units in one pack. |
| `pack_type` | text | null |  | Pack type label such as HLP, Soft Pack, Box or Dozen. |
| `entry_unit_default` | text | not null |  | Unit the memo screen enters quantities in by default. |
| `report_unit` | text | null |  | Unit shown in reports; null when the base unit is used. |
| `report_factor` | numeric(16,3) | not null |  | Base units per report unit, with three decimals. |
| `sort` | integer | not null |  | Display order among siblings. |
| `sales_enable` | boolean | not null |  | True when the SKU may be sold. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `thumbnail_media_uuid` | uuid | null |  | UUID of the thumbnail image. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (variant_id) REFERENCES app.product_node(id)`

## app.sku_price

Effective-dated price of a SKU for one of the five price types.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `price_type` | text | not null |  | Selling price type of the outlet or price row: outlet, cc, distributor (and reporting, nto for prices). |
| `amount_mtk` | bigint | not null |  | Amount in milli-taka (1 Tk = 1000 mtk). |
| `per_base_qty` | integer | not null |  | Number of base units the price covers; 1 for every seed price. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the price no longer applies; null means open. |
| `publish_batch_uuid` | uuid | null |  | Batch UUID of the price publish request that wrote the row, making a republish idempotent. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`

## app.stock_movement

Append-only stock event for one SKU (issue, return, adjustment, damage and others) with signed quantity.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `qty_entered` | integer | not null |  | Quantity as typed by the user, in unit_entered. |
| `unit_entered` | text | not null |  | Unit the user typed the quantity in: stick, piece, dozen or pack. |
| `pack_factor` | integer | not null |  | Base units per pack of the SKU at capture (copied from the SKU). |
| `qty_base` | integer | not null |  | Quantity in the SKU's base unit (sticks, pieces or dozens). |
| `reason_code` | text | null |  | Reason code from the matching business code list (app.code_list_item). |
| `slip_printed` | boolean | not null |  | True when a stock slip was printed for the movement. |
| `zone_id` | bigint | null |  | Zone of the route on the business date, frozen at capture (never rewritten by a later route move). |
| `cluster_id` | bigint | null |  | Cluster of the outlet on the business date, frozen at capture (null without an outlet). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.sub_channel

Outlet sub-channel within a channel.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `channel` | text | not null |  | Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (code)`; `PRIMARY KEY (id)`

## app.submit_void_event

Append-only record of a Sales Submit being voided by a TSO or above.

`owner: backend:sync | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `scope` | text | not null |  | What was voided: route_day or supervisor_day. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `subject_user_id` | bigint | null |  | Id of the AMO or TSO whose supervisor-day submit was voided; null for route-day voids. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `voided_cycle` | integer | not null |  | Submit cycle number that was voided. |
| `reason_code` | text | not null |  | Reason code from the matching business code list (app.code_list_item). |
| `note` | text | null |  | Free-text note. |
| `voided_by` | bigint | not null |  | Id of the user who voided the submit. |
| `voided_at` | timestamp with time zone | not null |  | UTC instant an admin data void tombstoned the row; null while live. Never deleted. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (route_id, business_date, voided_cycle)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (subject_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (voided_by) REFERENCES app.app_user(id)`

## app.supervisor_day

Attendance and Sales Submit state of an AMO or TSO for a date outside a route-day.

`owner: backend:sync | capture: SERVER | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `checked_in_at` | timestamp with time zone | null |  | UTC time of the first check-in of the day. |
| `checked_out_at` | timestamp with time zone | null |  | UTC time of the last check-out of the day. |
| `submit_received_at` | timestamp with time zone | null |  | UTC time the server received the day_submit. |
| `sales_submitted_at` | timestamp with time zone | null |  | UTC instant Sales Submit took effect. |
| `submit_cycle` | integer | not null |  | Sales Submit cycle: 1 for the first submit, plus one after each submit void. |
| `submit_voided` | boolean | not null |  | True when the submit was voided. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |

Keys: `UNIQUE (user_id, business_date)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.support_upload

One row is a phone database export a field user sent to Support (PDA to Support); the file is in Blob.

`owner: backend:masterdata | capture: OFFLINE | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `upload_uuid` | uuid | not null |  | Client-generated UUID of the upload; the API is idempotent by it. |
| `user_id` | bigint | not null |  | Database id of the user who sent the export, from the token. |
| `device_uuid` | uuid | not null |  | device_uuid of the phone that sent the export. |
| `bytes` | bigint | not null |  | Size of the export in bytes (at most 100 MiB). |
| `sha256` | bytea | not null |  | SHA-256 of the export file (32 bytes). |
| `app_version` | text | not null |  | App version name on the phone at export. |
| `last_sync_at` | timestamp with time zone | null |  | UTC time of the phone's last successful sync, as reported by the phone. |
| `pending_rows` | integer | null |  | Number of rows still in the phone's outbox at export. |
| `blob_path` | text | not null |  | Path of the export in the support Blob container (access restricted to Support). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |

Keys: `PRIMARY KEY (upload_uuid)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.survey

One row is a survey (POSM, AMO survey or TSO visit query); its current published version is in survey_version.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `version` | integer | not null |  | Current published version (survey_version.version); also the ETag. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | Last day the survey is in effect (inclusive); null means open-ended. |
| `points_per_photo` | integer | null |  | Points per accepted photo (deferred programme hook, docs/27; null when not used). |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `PRIMARY KEY (id)`

## app.survey_response

One answer to an in-visit survey question.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `survey_id` | bigint | not null |  | Id of the survey answered. |
| `survey_version` | integer | not null |  | Version of the survey answered. |
| `question_id` | bigint | not null |  | Id of the question answered. |
| `answer_type` | text | not null |  | Answer type: bool, num, option, text or photo_only. |
| `answer_bool` | boolean | null |  | Yes or no answer. |
| `answer_num` | numeric | null |  | Numeric answer. |
| `answer_option_code` | text | null |  | Code of the chosen option. |
| `answer_text` | text | null | personal | Free-text answer. |
| `photo_uuid` | uuid | null |  | Media uuid (app.media) of the photo attached to the row. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.survey_version

One row is an immutable published version of a survey: titles and questions; answers reference it.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `survey_id` | bigint | not null |  | Survey the version belongs to. |
| `version` | integer | not null |  | Version number, from 1 upwards per survey. |
| `title_en` | text | not null |  | English title. |
| `title_bn` | text | null |  | Bangla title. |
| `questions` | jsonb | not null |  | Array of questions (id, type, English and Bangla text, options). |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who published the version. |

Keys: `PRIMARY KEY (survey_id, version)`

References: `FOREIGN KEY (survey_id) REFERENCES app.survey(id)`

## app.sync_batch

Replay store of an uploaded batch with its fingerprint and stored response for the retention window.

`owner: backend:sync | capture: SERVER | retention: ops | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `device_id` | bigint | not null |  | Database id of the phone that captured the record, from the token. |
| `batch_uuid` | uuid | not null |  | UUID of the batch or bulk operation that carried or created the row (idempotency of the batch). |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `fingerprint` | bytea | not null |  | Hash of the record set of the batch; a different set under the same batch_uuid is refused. |
| `record_count` | integer | not null |  | Number of records in the batch. |
| `trigger` | text | null |  | What made the phone upload, for example foreground, periodic or connectivity. |
| `attempt` | integer | null |  | Attempt number of the upload on the phone. |
| `app_version` | text | null |  | App version as versionName+versionCode, e.g. 1.0.3+103. |
| `pending_rows` | integer | null |  | Rows still waiting on the phone after this batch. |
| `counts` | jsonb | not null |  | JSON counts of records accepted, duplicate, rejected and quarantined in the batch. |
| `response_gz` | bytea | null |  | Stored batch response as gzip-compressed JSON, replayed on retry. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `completed_at` | timestamp with time zone | null |  | UTC time processing of the batch finished. |
| `replay_count` | integer | not null |  | Times the stored response was replayed for a retry. |
| `expires_at` | timestamp with time zone | not null |  | UTC instant after which the row is no longer valid. |

Keys: `PRIMARY KEY (device_id, batch_uuid)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.sync_quarantine

Records held for a human decision with their payload and resolution.

`owner: backend:sync | capture: SERVER | retention: quarantine | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `record_type` | text | not null |  | Sync record type (contract RecordType). |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `payload_sha256` | bytea | not null |  | SHA-256 of the RFC 8785 canonical JSON of the record; same uuid and hash means duplicate. |
| `payload` | jsonb | not null | personal | The SyncRecord as received (JSON); may hold fixes, names and phone numbers. |
| `fixed_payload` | jsonb | null | personal | The corrected record that was re-ingested on accept_with_fix. |
| `detail` | text | null | personal | Human-readable detail of why the record was quarantined. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `batch_uuid` | uuid | null |  | UUID of the batch or bulk operation that carried or created the row (idempotency of the batch). |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `resolution_uuid` | uuid | null |  | UUID of the resolve command, making resolution idempotent. |
| `resolved_by_user_id` | bigint | null |  | Id of the user who resolved the item. |
| `approved_by_user_id` | bigint | null |  | Id of the second user who approved accept_with_fix for data-entry classes. |
| `resolved_at` | timestamp with time zone | null |  | UTC time the item was resolved. |
| `resolution_note` | text | null | personal | Free-text note entered with the resolution. |

Keys: `UNIQUE (client_uuid, payload_sha256)`; `UNIQUE (resolution_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (approved_by_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (resolved_by_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.sync_rejected

Records rejected or parked at ingest with the payload as received and the reason code.

`owner: backend:sync | capture: SERVER | retention: quarantine | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | UUID v4 generated on the phone when the record was committed; the idempotency key of sync. |
| `record_type` | text | not null |  | Sync record type (contract RecordType). |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `retryable` | boolean | not null |  | True when the record may be accepted on a later resend. |
| `payload_sha256` | bytea | not null |  | SHA-256 of the RFC 8785 canonical JSON of the record; same uuid and hash means duplicate. |
| `payload` | jsonb | not null | personal | The record as received (JSON); may hold fixes, names and phone numbers. |
| `detail` | text | null |  | Human-readable detail of the rejection. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `device_id` | bigint | null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `batch_uuid` | uuid | null |  | UUID of the batch or bulk operation that carried or created the row (idempotency of the batch). |
| `attempts` | integer | not null |  | Number of times the record was received and rejected. |
| `first_received_at` | timestamp with time zone | not null |  | UTC time the record was first received. |
| `last_received_at` | timestamp with time zone | not null |  | UTC time the record was last received. |
| `parked_until` | timestamp with time zone | null |  | UTC time a parked record turns final, after cfg.sync.parked_ttl_days. |
| `finalised_at` | timestamp with time zone | null |  | UTC time the rejection became final. |
| `stored_at` | timestamp with time zone | null |  | UTC time a later resend of the record was accepted. |

Keys: `UNIQUE (client_uuid, payload_sha256)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.target

Live target of a route or zone for a product and month; revisions close and replace rows.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `target_set_id` | bigint | not null |  | Id of the target set the row belongs to. |
| `revision_id` | bigint | not null |  | Id of the revision that wrote the row. |
| `month` | date | not null |  | First day of the calendar month. |
| `scope_type` | text | not null |  | Level of the scope: global, role, wing, division, territory, geo_class, zone, route, outlet, user or device. |
| `scope_id` | bigint | not null |  | Id of the scope node; 0 for global; role and geo_class use the app.role_def and app.geo_class_def ordinals. |
| `product_level` | text | not null |  | Level of the product: category, brand, variant or sku. |
| `product_id` | bigint | not null |  | Id of the product node at that level. |
| `std_target` | numeric(16,3) | not null |  | Target in standard units, with three decimals. |
| `std_unit` | text | not null |  | Unit the standard target is expressed in. |
| `memo_target` | integer | not null |  | Target number of memos. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `superseded_by_revision_id` | bigint | null |  | Id of the revision that replaced this row; null while live. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (revision_id) REFERENCES app.target_revision(id)`; `FOREIGN KEY (superseded_by_revision_id) REFERENCES app.target_revision(id)`; `FOREIGN KEY (target_set_id) REFERENCES app.target_set(id)`

## app.target_revision

One revision of a target set, with the change reason and stored result for idempotent replay.

`owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `target_set_id` | bigint | not null |  | Id of the target set revised. |
| `revision_no` | integer | not null |  | Sequence number of the revision within the set. |
| `batch_uuid` | uuid | not null |  | Batch UUID of the target write request; a replay returns the first result. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `change_reason` | text | null |  | Reason recorded for the revision. |
| `result` | jsonb | null |  | JSON result stored for replays of the write request. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `UNIQUE (batch_uuid)`; `UNIQUE (target_set_id, revision_no)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (target_set_id) REFERENCES app.target_set(id)`

## app.target_set

Targets of a route or zone for one month.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `month` | date | not null |  | First day of the month the targets are for. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |

Keys: `UNIQUE (month)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (created_by) REFERENCES app.app_user(id)`

## app.task

A task created by a supervisor for a user, optionally tied to an outlet or visit.

`owner: backend:notify | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `task_type_code` | text | not null |  | Code of the task type from the code list. |
| `assignee_user_id` | bigint | not null |  | Id of the user the task is assigned to. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `title` | text | not null |  | Short title of the task. |
| `description` | text | null | personal | Free-text description. |
| `due_date` | date | null |  | Date the task is due. |
| `source_visit_client_uuid` | uuid | null |  | Client UUID of the visit the task came from. |
| `source` | text | not null |  | Where the row came from; allowed values are listed under constraints. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `status_changed_at` | timestamp with time zone | null |  | UTC instant of the last status change. |
| `cancelled_by` | bigint | null |  | Id of the user who cancelled the task. |
| `route_id` | bigint | null |  | Route the task belongs to (contract Task.route_id); null when the task names none. |
| `cancel_reason` | text | null |  | Reason given when the task was cancelled (10 to 500 characters); set once, with the cancellation. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (assignee_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (cancelled_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.task_event

Resolve or reopen of a task by its assignee.

`owner: backend:notify | capture: OFFLINE | retention: transaction | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `task_uuid` | uuid | not null |  | Client UUID of the task the event is about. |
| `event` | text | not null |  | Event: resolved or reopened. |
| `note` | text | null | personal | Free-text note. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.territory

Geography level below a division; groups zones.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `division_id` | bigint | not null |  | Id of the division the territory belongs to. |
| `email` | text | null |  | Business e-mail of the territory office. |
| `address` | text | null |  | Office address of the territory. |
| `pda_contact_no` | text | null |  | Business contact number of the office or zone phone. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (division_id) REFERENCES app.division(id)`

## app.tutorial

One row is a tutorial video or manual shown to the listed roles in the apps and on the web.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `version` | integer | not null |  | Optimistic-concurrency version (ETag); the API raises it by one on every update. |
| `kind` | text | not null |  | Kind of the row; allowed values are listed under constraints. |
| `title_en` | text | not null |  | English title. |
| `title_bn` | text | null |  | Bangla title. |
| `asset_id` | uuid | not null |  | Uploaded admin asset holding the video or PDF. |
| `roles` | text[] | not null |  | Roles that see the tutorial. |
| `sort` | integer | not null |  | Display order among the tutorials. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (asset_id) REFERENCES app.admin_asset(asset_id)`

## app.user_consent

Acceptance of a notice such as the location notice by a user on a phone.

`owner: backend:auth | capture: OFFLINE | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `policy_key` | text | not null |  | Notice accepted; location_notice for now. |
| `policy_version` | integer | not null |  | Version of the notice accepted. |
| `accepted` | boolean | not null |  | True when the user accepted the notice. |
| `locale` | text | not null |  | Language the notice was shown in: bn or en. |
| `shown_at` | timestamp with time zone | not null |  | UTC time the notice was shown. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.user_scope

Supervisory reach of a user: a geography node, effective-dated.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `node_type` | text | not null |  | Type of the scope node: national, wing, division, territory or zone. |
| `node_id` | bigint | not null |  | Id of the scope node; 0 for national. |
| `valid_from` | date | not null |  | First day the row is in effect. |
| `valid_to` | date | null |  | First date the scope no longer applies; null means open. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `reason` | text | null |  | Reason given for the change or action (free text). |

Keys: `PRIMARY KEY (id)`

References: `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.v_dirty_key_dead

Dead rebuild keys per kind, for the sync-health page and alerting.

`owner: db | capture: SERVER | retention: ops | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `kind` | text | null |  | Kind of the rebuild key. |
| `dead_keys` | bigint | null |  | Number of dead keys of the kind. |
| `oldest_dead_at` | timestamp with time zone | null |  | UTC time the oldest of them was parked. |

## app.visit

One outlet visit with the phone's and the server's geo verdicts, distance, outcome and close data.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `sig` | text | null |  | ES256 signature by the device key over the record (header records only). |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `visit_kind` | text | not null |  | Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry (route tables: daily, 3f or 2f). |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `opened_at` | timestamp with time zone | not null |  | UTC time the visit was opened on the phone. |
| `sequence_no` | integer | not null |  | Order of the row within its parent or day. |
| `planned` | boolean | not null |  | True when the route or outlet was planned for the business date. |
| `assessed_user_id` | bigint | null |  | Id of the user assessed during an AMO or TSO call. |
| `fix_status` | text | null |  | Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable. |
| `fix_lat` | double precision | null | personal | Latitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_lng` | double precision | null | personal | Longitude of the user's location fix taken with the record (WGS84 degrees). |
| `fix_accuracy_m` | double precision | null | personal | Accuracy in metres of the location fix taken with the record. |
| `fix_is_mock` | boolean | null |  | True when the phone flagged the fix as coming from a mock location provider. |
| `verdict` | text | not null |  | Phone's geo verdict: in_range, out_of_range, accuracy_too_low, no_fix, mocked or no_outlet_location. |
| `distance_m` | double precision | null |  | Distance in metres between the fix and the outlet as computed on the phone. |
| `radius_m_used` | integer | not null |  | Geofence radius in metres the phone applied. |
| `max_accuracy_m_used` | integer | not null |  | Maximum accepted fix accuracy in metres the phone applied. |
| `location_basis` | text | not null |  | Quality of the outlet location used for the check: master, provisional, placeholder or none. |
| `outlet_lat` | double precision | null |  | Latitude in degrees of the outlet as the phone had it. |
| `outlet_lng` | double precision | null |  | Longitude in degrees of the outlet as the phone had it. |
| `geo_action` | text | not null |  | Action taken: sale_allowed, force_sale or blocked. |
| `force_reason_code` | text | null |  | Reason code given for a forced sale. |
| `force_photo_uuid` | uuid | null |  | UUID of the photo taken for a forced sale. |
| `server_verdict` | text | null |  | Server's re-check verdict, same values as the phone's verdict. |
| `server_distance_m` | double precision | null |  | Distance in metres computed by the server re-check. |
| `server_radius_m` | integer | null |  | Geofence radius in metres the server applied. |
| `server_max_accuracy_m` | integer | null |  | Maximum accepted accuracy in metres the server applied. |
| `server_checked_at` | timestamp with time zone | null |  | UTC time the server re-check ran. |
| `close_client_uuid` | uuid | null |  | Client UUID of the visit_close record. |
| `close_received_at` | timestamp with time zone | null |  | UTC time the server received the visit_close. |
| `close_captured_at` | timestamp with time zone | null |  | UTC time the visit was closed on the phone. |
| `outcome_code` | text | null |  | Outcome of the visit or check; allowed values are listed under constraints. |
| `call_started_at` | timestamp with time zone | null |  | UTC time the call started on the phone. |
| `call_declined` | boolean | null |  | True when the retailer declined the call. |
| `ended_at` | timestamp with time zone | null |  | UTC instant the activity ended. |
| `is_zero_sale` | boolean | null |  | True when the visit ended without a sale. |
| `zone_id` | bigint | null |  | Zone of the route on the business date, frozen at capture (never rewritten by a later route move). |
| `cluster_id` | bigint | null |  | Cluster of the outlet on the business date, frozen at capture (null without an outlet). |
| `outlet_channel` | text | null |  | Outlet channel code at capture (as the outlet master held it at ingest). |
| `outlet_geo_class` | text | null |  | Outlet geo class code at capture (as the outlet master held it at ingest). |

Keys: `UNIQUE (client_uuid, business_date)`; `UNIQUE (external_ref, business_date)`; `PRIMARY KEY (id, business_date)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (assessed_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.visit_plan

A TSO visit plan for a date.

`owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `plan_date` | date | not null |  | Dhaka date the plan is for. |
| `note` | text | null |  | Free-text note. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.visit_plan_outlet

An outlet included in a TSO visit plan.

`owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `plan_client_uuid` | uuid | not null |  | Client UUID of the visit plan this outlet belongs to. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.visit_skip

An outlet of the day's route not visited, with a reason.

`owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Client-generated UUID v4 of the record; the server upserts by it (idempotency key). |
| `family_uuid` | uuid | not null |  | client_uuid of the family header the record belongs to (equal to client_uuid on a header). |
| `business_date` | date | not null |  | Asia/Dhaka date of the trusted capture time. |
| `business_date_device` | date | null |  | Date on the phone's own clock, kept when the server re-dated the row to a trusted business date. |
| `user_id` | bigint | not null |  | Database id of the user who captured the record, taken from the token and never from the body. |
| `device_id` | bigint | null |  | Database id of the phone that captured the record, from the token. |
| `acting_for_user_id` | bigint | null |  | Assignee of the route when the capturing user worked it as a cover substitute. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `captured_elapsed_ms` | bigint | null |  | SystemClock.elapsedRealtime() at capture, in milliseconds since boot. |
| `boot_count` | integer | null |  | Android boot counter at capture; with captured_elapsed_ms it anchors trusted time. |
| `clock_offset_ms` | bigint | null |  | Server time minus phone wall clock known at capture, in ms; null if the phone never synced. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `schema_version` | integer | not null |  | Version of the record payload schema. |
| `config_version` | bigint | not null |  | Global config version in force for the row (at capture for device records). |
| `bundle_version` | text | null |  | Version of the day bundle the phone held at capture (<date>:<seq>). |
| `bundle_stale` | boolean | not null |  | True when the record was captured on a cached bundle older than the business date. |
| `first_batch_uuid` | uuid | null |  | batch_uuid of the sync batch that first delivered the record. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `voided_at` | timestamp with time zone | null |  | UTC time an admin data void tombstoned the row; the row is never deleted. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `reason_code` | text | not null |  | Reason code from the matching business code list (app.code_list_item). |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (acting_for_user_id) REFERENCES app.app_user(id)`; `FOREIGN KEY (device_id) REFERENCES app.device(id)`; `FOREIGN KEY (outlet_id) REFERENCES app.outlet(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (user_id) REFERENCES app.app_user(id)`

## app.web_entry_line

One row is the per-SKU quantities of a route-day web entry.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Server-generated UUID of the line (the browser sends lines without one); data void reports it. |
| `entry_client_uuid` | uuid | not null |  | client_uuid of the route-day entry the line belongs to. |
| `route_id` | bigint | not null |  | Route of the entry (equal to the entry's by foreign key). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the entry (equal to the entry's by foreign key). |
| `sku_id` | bigint | not null |  | SKU of the line. |
| `issue_qty_base` | bigint | not null |  | Quantity issued to the SR, in the SKU's base unit (sticks, pieces or dozens). |
| `return_qty_base` | bigint | not null |  | Quantity returned by the SR, in the base unit; at most the issue (sale = issue minus return). |
| `memo_count` | integer | not null |  | Number of memos the SKU was sold on. |
| `class_qty_base` | jsonb | not null |  | Sale split by web-entry class (cfg.web.entry_classes): sub-channel id to base quantity. |
| `voided_at` | timestamp with time zone | null |  | UTC time a data void voided the line (tombstone); written once. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (entry_client_uuid, sku_id)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (entry_client_uuid, route_id, business_date) REFERENCES app.web_entry_route_day(client_uuid, route_id, business_date)`; `FOREIGN KEY (sku_id) REFERENCES app.sku(id)`

## app.web_entry_route_day

One row is a back-office web entry of a route-day (issue, return and memos per SKU, successful calls); a re-save is a new row that closes the old one.

`owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `client_uuid` | uuid | not null |  | Browser-generated UUID of the save; the API is idempotent by it. |
| `supersedes_client_uuid` | uuid | null |  | client_uuid of the entry this re-save replaces (null for the first save). |
| `route_id` | bigint | not null |  | Route of the entered route-day. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the entered route-day. |
| `successful_calls` | integer | not null |  | Successful calls entered (at most target_outlets when cfg.web.entry_validate_calls_le_target). |
| `target_outlets` | integer | not null |  | Target outlets of the route-day at the save, as the server computed them. |
| `app_overlap` | boolean | not null |  | True when app memos existed for the same route-day at the save (flagged, never added). |
| `change_reason` | text | null |  | Reason given for a re-save (required when supersedes_client_uuid is set). |
| `source` | text | not null |  | Origin of the entry; always web. |
| `entered_by` | bigint | not null |  | User who saved the entry. |
| `entered_at` | timestamp with time zone | not null |  | UTC instant of the save. |
| `replaced_at` | timestamp with time zone | null |  | UTC time a re-save replaced this entry (null while it is live); written once. |
| `replaced_by` | bigint | null |  | User whose re-save replaced this entry; written once with replaced_at. |
| `voided_at` | timestamp with time zone | null |  | UTC time a data void voided the entry (tombstone); written once. |

Keys: `UNIQUE (client_uuid)`; `UNIQUE (client_uuid, route_id, business_date)`; `UNIQUE (supersedes_client_uuid)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (entered_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (replaced_by) REFERENCES app.app_user(id)`; `FOREIGN KEY (route_id) REFERENCES app.route(id)`; `FOREIGN KEY (supersedes_client_uuid, route_id, business_date) REFERENCES app.web_entry_route_day(client_uuid, route_id, business_date)`

## app.wing

Top level of the sales geography.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `email` | text | null |  | Business e-mail of the wing office. |
| `address` | text | null |  | Office address of the wing. |
| `pda_contact_no` | text | null |  | Business contact number of the office or zone phone. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

## app.zone

Geography level below a territory; the unit of Final Submit and day rollups.

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `id` | bigint | not null |  | Server surrogate key. |
| `code` | text | not null |  | Stable business code of the row, unique within its table. |
| `name` | text | not null |  | Display name. |
| `name_bn` | text | null |  | Display name in Bangla. |
| `territory_id` | bigint | not null |  | Territory (app.territory). |
| `house_id` | bigint | null |  | Id of the distribution house of the zone. |
| `dep_name` | text | null |  | Name of the DEP (distribution entry point) for the zone, as a free-text label. |
| `email` | text | null |  | Business e-mail of the zone office. |
| `address` | text | null |  | Office address of the zone. |
| `pda_contact_no` | text | null |  | Business contact number of the office or zone phone. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `external_ref` | character varying(64) | null |  | Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set. |
| `created_at` | timestamp with time zone | not null |  | UTC instant the row was inserted on the server. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `version` | integer | not null |  | Optimistic-concurrency version; increases by one on every update. |
| `created_by` | bigint | null |  | User who created the row (null for migrations and jobs). |
| `updated_by` | bigint | null |  | User who last updated the row. |

Keys: `UNIQUE (code)`; `UNIQUE (external_ref)`; `PRIMARY KEY (id)`

References: `FOREIGN KEY (house_id) REFERENCES app.house(id)`; `FOREIGN KEY (territory_id) REFERENCES app.territory(id)`

## dw.agg_daily_outlet

Per outlet and business date: whether visited, geo-valid, and sales totals.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `visited` | boolean | not null |  | True when the outlet was visited that day. |
| `geo_valid` | boolean | not null |  | True when a visit that day passed the geo check. |
| `active_memo_count` | integer | not null |  | Number of active (not voided or superseded) memos with at least one line. |
| `sold_qty_base` | bigint | not null |  | Quantity sold in base units. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `due_mtk` | bigint | not null |  | Amount left on credit (due) in milli-taka. |
| `dues_collected_mtk` | bigint | not null |  | Dues collected against earlier credit memos, in milli-taka. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, outlet_id)`

## dw.agg_daily_route

Per route and business date: day state, visit counts, sales, discounts, paid and due money.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `planned` | boolean | not null |  | True when the route or outlet was planned for the business date. |
| `exception_approved` | boolean | not null |  | True when a day exception was approved for the route-day. |
| `day_state` | text | not null |  | Route-day state at the time of the last event. |
| `logged_in_at` | timestamp with time zone | null |  | UTC time of the route's first day_open. |
| `sales_submitted_at` | timestamp with time zone | null |  | UTC instant Sales Submit took effect. |
| `final_submitted_at` | timestamp with time zone | null |  | UTC time of the Final Submit. |
| `target_outlets` | integer | not null |  | Outlets planned for the route-day, frozen at the first bundle of the day (strike-rate denominator). |
| `visited_outlets` | integer | not null |  | Distinct outlets visited. |
| `successful_calls` | integer | not null |  | Visits with a sale. |
| `visits` | integer | not null |  | Number of visits. |
| `geo_valid_visits` | integer | not null |  | Visits whose geo verdict was in range. |
| `force_sale_visits` | integer | not null |  | Visits where a sale was forced past the geofence. |
| `mock_visits` | integer | not null |  | Visits with a mock-location verdict. |
| `suspicious_visits` | integer | not null |  | Visits that belong to suspicious user-days under the risk rules. |
| `active_memo_count` | integer | not null |  | Number of active (not voided or superseded) memos with at least one line. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `offer_discount_mtk` | bigint | not null |  | Offer and free-goods discount in milli-taka (zero until the discount engine exists, docs/27). |
| `drp_discount_mtk` | bigint | not null |  | DRP (empty-pack slide) discount in milli-taka. |
| `qc_deduction_mtk` | bigint | not null |  | QC settlement deducted on the memo, in milli-taka. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `paid_mtk` | bigint | not null |  | Amount paid in cash at the memo, in milli-taka. |
| `due_mtk` | bigint | not null |  | Amount left on credit (due) in milli-taka. |
| `dues_collected_mtk` | bigint | not null |  | Dues collected against earlier credit memos, in milli-taka. |
| `late_rows_after_final` | integer | not null |  | Rows that arrived after the Final Submit. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, route_id)`

## dw.agg_daily_route_brand

Per route, brand and date: memo count containing the brand and its sales.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `brand_id` | bigint | not null |  | Product brand (app.product_node of level brand). |
| `memo_count` | integer | not null |  | Active memos with at least one line of the brand. |
| `sold_qty_base` | bigint | not null |  | Quantity sold in base units. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, route_id, brand_id)`

## dw.agg_daily_route_segment

Per route, product segment and date: memo count containing the segment (each memo once) and its sales.

`owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `segment_id` | bigint | not null |  | Product segment (app.product_node of level segment). |
| `memo_count` | integer | not null |  | Active memos with at least one line in the segment, each memo counted once. |
| `sold_qty_base` | bigint | not null |  | Quantity sold in the segment, in each SKU's base unit (sticks, pieces or dozens). |
| `gross_mtk` | bigint | not null |  | Gross sales of the segment in integer milli-taka. |
| `last_event_id` | bigint | not null |  | Last outbox event folded in (informational; the row is recomputed by dirty key). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last recompute. |

Keys: `PRIMARY KEY (business_date, route_id, segment_id)`

## dw.agg_daily_route_sku

Per route, SKU and date: sold, free, issued and returned quantities and sales.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `sold_qty_base` | bigint | not null |  | Quantity sold in base units. |
| `free_qty_base` | bigint | not null |  | Free quantity given, in base units. |
| `issued_qty_base` | bigint | not null |  | Quantity issued to the route, in base units. |
| `returned_qty_base` | bigint | not null |  | Quantity returned, in base units. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `memo_count` | integer | not null |  | Active memos containing the SKU. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, route_id, sku_id)`

## dw.agg_daily_screen_use

One row is the use of one screen action by one role on one day, rolled up from fact_activity and kept for ever.

`owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date. |
| `role` | text | not null |  | Role of the users counted. |
| `screen` | text | not null |  | Screen key. |
| `action` | text | not null |  | Action key. |
| `users` | integer | not null |  | Distinct users with at least one such event that day. |
| `events` | integer | not null |  | Number of such events that day (duplicates removed). |

Keys: `PRIMARY KEY (business_date, role, screen, action)`

## dw.agg_daily_zone

Per zone and business date: route and visit counts, geo and risk counts, and sales totals.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `territory_id` | bigint | not null |  | Territory (app.territory). |
| `target_routes` | integer | not null |  | Routes planned in the zone that day. |
| `logged_in_routes` | integer | not null |  | Routes that logged in. |
| `sales_submitted_routes` | integer | not null |  | Routes whose Sales Submit is in effect. |
| `final_submitted` | boolean | not null |  | True when the zone-day has a Final Submit. |
| `target_outlets` | integer | not null |  | Outlets planned for the route-day, frozen at the first bundle of the day (strike-rate denominator). |
| `visited_outlets` | integer | not null |  | Distinct outlets visited in the zone. |
| `successful_calls` | integer | not null |  | Visits with a sale. |
| `visits` | integer | not null |  | Number of visits. |
| `geo_valid_visits` | integer | not null |  | Visits whose geo verdict was in range. |
| `force_sale_visits` | integer | not null |  | Visits forced past the geofence. |
| `mock_visits` | integer | not null |  | Visits with a mock-location verdict. |
| `suspicious_visits` | integer | not null |  | Visits of user-days flagged suspicious by the risk rules. |
| `suspicious_user_days` | integer | not null |  | User-days flagged suspicious by the risk rules. |
| `active_memo_count` | integer | not null |  | Number of active (not voided or superseded) memos with at least one line. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `dues_collected_mtk` | bigint | not null |  | Dues collected against earlier credit memos, in milli-taka. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, zone_id)`

## dw.agg_hourly_zone

Per zone and hour: records received and sales, for the live dashboard.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `hour_of_day` | smallint | not null |  | Dhaka hour of the day, 0 to 23. |
| `visits` | integer | not null |  | Number of visits. |
| `active_memo_count` | integer | not null |  | Number of active (not voided or superseded) memos with at least one line. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `records_received` | integer | not null |  | Records the server received in that hour. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, zone_id, hour_of_day)`

## dw.dim_date

Calendar dimension keyed by date with week, month, quarter and holiday attributes.

`owner: db | capture: REFERENCE | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `iso_weekday` | smallint | not null |  | ISO weekday number, 1 Monday to 7 Sunday; 5 is Friday. |
| `visit_day_bit` | smallint | not null |  | Bit of route.visit_days_mask for the date, 0 for Saturday. |
| `month` | date | not null |  | First day of the calendar month. |
| `quarter` | text | not null |  | Quarter label such as 2026-Q4. |
| `iso_week` | smallint | not null |  | ISO week number of the date. |
| `is_weekend` | boolean | not null |  | True when the date is a weekend day per the cfg.calendar.weekend_days default (Friday). |
| `is_holiday` | boolean | not null |  | True when the date is a holiday, refreshed from app.calendar_holiday. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date)`

## dw.dim_geo

Route-level geography dimension flattened through zone, territory, division and wing.

`owner: worker | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `route_code` | text | not null |  | Code of the route. |
| `route_name` | text | not null |  | Name of the route. |
| `route_kind` | text | not null |  | Kind of the route. |
| `visit_kind` | text | null |  | Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry (route tables: daily, 3f or 2f). |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `zone_name` | text | not null |  | Name of the zone. |
| `territory_id` | bigint | not null |  | Territory (app.territory). |
| `territory_name` | text | not null |  | Name of the territory. |
| `division_id` | bigint | not null |  | Id of the division. |
| `division_name` | text | not null |  | Name of the division. |
| `wing_id` | bigint | not null |  | Id of the wing. |
| `wing_name` | text | not null |  | Name of the wing. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (route_id)`

## dw.dim_geo_version

One row is a version of geo valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_geo.

`owner: db | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `geo_key` | bigint | not null |  | Surrogate key of the version; facts store it. |
| `route_id` | bigint | not null |  | Route (app.route) being worked. |
| `route_code` | text | not null |  | Code of the route. |
| `route_name` | text | not null |  | Name of the route. |
| `route_kind` | text | not null |  | Kind of the route. |
| `visit_kind` | text | null |  | Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry (route tables: daily, 3f or 2f). |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `zone_name` | text | not null |  | Name of the zone. |
| `territory_id` | bigint | not null |  | Territory (app.territory). |
| `territory_name` | text | not null |  | Name of the territory. |
| `division_id` | bigint | not null |  | Id of the division. |
| `division_name` | text | not null |  | Name of the division. |
| `wing_id` | bigint | not null |  | Id of the wing. |
| `wing_name` | text | not null |  | Name of the wing. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `valid_from` | date | null |  | First Asia/Dhaka business date of the version (null = since ever). |
| `valid_to` | date | null |  | Asia/Dhaka business date the version ended, exclusive (null = current). |
| `is_current` | boolean | null |  | True for the current version (valid_to is null). |

Keys: `PRIMARY KEY (geo_key)`

## dw.dim_outlet

Outlet dimension for reports.

`owner: worker | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `outlet_code` | text | not null |  | Code of the outlet. |
| `outlet_name` | text | not null |  | Name of the outlet. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `cluster_id` | bigint | not null |  | Outlet cluster (market group) inside the zone. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `channel` | text | not null |  | Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa. |
| `sub_channel_id` | bigint | null |  | Outlet sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `outlet_kind` | text | not null |  | Outlet kind: retail or wholesale. |
| `location_confirmed` | boolean | not null |  | True when the outlet location is confirmed. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (outlet_id)`

## dw.dim_outlet_version

One row is a version of outlet valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_outlet.

`owner: db | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `outlet_key` | bigint | not null |  | Surrogate key of the version; facts store it. |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `outlet_code` | text | not null |  | Code of the outlet. |
| `outlet_name` | text | not null |  | Name of the outlet. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `cluster_id` | bigint | not null |  | Outlet cluster (market group) inside the zone. |
| `zone_id` | bigint | not null |  | Zone (app.zone). |
| `channel` | text | not null |  | Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa. |
| `sub_channel_id` | bigint | null |  | Outlet sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `outlet_kind` | text | not null |  | Outlet kind: retail or wholesale. |
| `location_confirmed` | boolean | not null |  | True when the outlet location is confirmed. |
| `status` | text | not null |  | Lifecycle status; allowed values are listed under constraints. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `valid_from` | date | null |  | First Asia/Dhaka business date of the version (null = since ever). |
| `valid_to` | date | null |  | Asia/Dhaka business date the version ended, exclusive (null = current). |
| `is_current` | boolean | null |  | True for the current version (valid_to is null). |

Keys: `PRIMARY KEY (outlet_key)`

## dw.dim_product

SKU dimension flattened through variant, brand, segment and category.

`owner: worker | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `sku_code` | text | not null |  | Code of the SKU. |
| `short_name` | text | not null |  | Short name printed on the memo. |
| `base_unit` | text | not null |  | Unit of the SKU's quantities. |
| `base_per_pack` | integer | not null |  | Base units in one pack. |
| `variant_id` | bigint | not null |  | Id of the variant node. |
| `variant_name` | text | not null |  | Name of the variant. |
| `brand_id` | bigint | not null |  | Product brand (app.product_node of level brand). |
| `brand_name` | text | not null |  | Name of the brand. |
| `segment_id` | bigint | not null |  | Id of the segment node. |
| `segment_name` | text | not null |  | Name of the segment. |
| `category_id` | bigint | not null |  | Id of the category node. |
| `category_name` | text | not null |  | Name of the category. |
| `category_code` | text | not null |  | Product category: cigarette, bidi, lighter or match. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (sku_id)`

## dw.dim_product_version

One row is a version of product valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_product.

`owner: db | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `product_key` | bigint | not null |  | Surrogate key of the version; facts store it. |
| `sku_id` | bigint | not null |  | SKU (app.sku). |
| `sku_code` | text | not null |  | Code of the SKU. |
| `short_name` | text | not null |  | Short name printed on the memo. |
| `base_unit` | text | not null |  | Unit of the SKU's quantities. |
| `base_per_pack` | integer | not null |  | Base units in one pack. |
| `variant_id` | bigint | not null |  | Id of the variant node. |
| `variant_name` | text | not null |  | Name of the variant. |
| `brand_id` | bigint | not null |  | Product brand (app.product_node of level brand). |
| `brand_name` | text | not null |  | Name of the brand. |
| `segment_id` | bigint | not null |  | Id of the segment node. |
| `segment_name` | text | not null |  | Name of the segment. |
| `category_id` | bigint | not null |  | Id of the category node. |
| `category_name` | text | not null |  | Name of the category. |
| `category_code` | text | not null |  | Product category: cigarette, bidi, lighter or match. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `valid_from` | date | null |  | First Asia/Dhaka business date of the version (null = since ever). |
| `valid_to` | date | null |  | Asia/Dhaka business date the version ended, exclusive (null = current). |
| `is_current` | boolean | null |  | True for the current version (valid_to is null). |

Keys: `PRIMARY KEY (product_key)`

## dw.dim_user

One row is the current state of a user for reporting (role, designation, home zone, status); no names or contacts.

`owner: backend:analytics | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_id` | bigint | not null |  | User (app.app_user.id). |
| `role` | text | not null |  | Role code of the user. |
| `designation` | text | null |  | Designation code of the user (null if none). |
| `home_zone_id` | bigint | null |  | Home zone of the user (null if none). |
| `status` | text | not null |  | User status (active, disabled). |
| `pilot` | boolean | not null |  | True for pilot users. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (user_id)`

## dw.dim_user_version

One row is a version of user valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.dim_user.

`owner: db | capture: SERVER | retention: master | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_key` | bigint | not null |  | Surrogate key of the version; facts store it. |
| `user_id` | bigint | not null |  | User (app.app_user.id). |
| `role` | text | not null |  | Role code of the user. |
| `designation` | text | null |  | Designation code of the user (null if none). |
| `home_zone_id` | bigint | null |  | Home zone of the user (null if none). |
| `status` | text | not null |  | User status (active, disabled). |
| `pilot` | boolean | not null |  | True for pilot users. |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `valid_from` | date | null |  | First Asia/Dhaka business date of the version (null = since ever). |
| `valid_to` | date | null |  | Asia/Dhaka business date the version ended, exclusive (null = current). |
| `is_current` | boolean | null |  | True for the current version (valid_to is null). |

Keys: `PRIMARY KEY (user_key)`

## dw.fact_activity

One row is one screen or action event from a phone's activity log.

`owner: backend:analytics | capture: SERVER | retention: telemetry | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_key` | bigint | not null |  | User of the event (app.app_user.id; no user dimension table). |
| `device_key` | bigint | not null |  | Phone of the event (app.device.id; no device dimension table). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the event. |
| `occurred_at` | timestamp with time zone | not null |  | UTC time of the event on the phone. |
| `role` | text | not null |  | Role of the user at the event. |
| `screen` | text | not null |  | Screen key of the event. |
| `action` | text | not null |  | Action key of the event. |
| `seq` | integer | not null |  | Position of the event in the source activity_log row's event array. |
| `source_uuid` | uuid | not null |  | client_uuid of the source app.activity_log row. |

Keys: `PRIMARY KEY (user_key, business_date, occurred_at, seq)`

## dw.fact_attendance

One row per user and business date with the day's check-in and check-out and their fixes, filled by the worker from attendance_event.

`owner: worker | capture: SERVER | retention: event_fact | pii: personal` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `role` | text | not null |  | User role (contract Role). |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `check_in_at` | timestamp with time zone | null |  | UTC instant of the day's check-in. |
| `check_in_lat` | double precision | null | personal | Latitude of the check-in fix (WGS84 degrees). |
| `check_in_lng` | double precision | null | personal | Longitude of the check-in fix (WGS84 degrees). |
| `check_in_accuracy_m` | double precision | null | personal | Accuracy in metres of the check-in fix. |
| `check_in_is_mock` | boolean | null |  | True when the check-in fix came from a mock location provider. |
| `check_out_at` | timestamp with time zone | null |  | UTC instant of the day's check-out. |
| `check_out_lat` | double precision | null | personal | Latitude of the check-out fix (WGS84 degrees). |
| `check_out_lng` | double precision | null | personal | Longitude of the check-out fix (WGS84 degrees). |
| `check_out_accuracy_m` | double precision | null | personal | Accuracy in metres of the check-out fix. |
| `check_out_is_mock` | boolean | null |  | True when the check-out fix came from a mock location provider. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, user_id)`

## dw.fact_consent

One row is a user's acceptance of a policy version (employee-location notice and other policies).

`owner: backend:analytics | capture: SERVER | retention: audit | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `user_key` | bigint | not null |  | User who accepted (app.app_user.id; no user dimension table). |
| `policy_version` | text | not null |  | Policy key and version accepted. |
| `accepted_at` | timestamp with time zone | not null |  | UTC time of the acceptance. |
| `device_key` | bigint | null |  | Phone the acceptance was made on (app.device.id); null on the web. |
| `business_date` | date | not null |  | Asia/Dhaka business date of the acceptance. |
| `text_sha256` | bytea | null |  | SHA-256 of the policy text shown. |
| `source_uuid` | uuid | not null |  | client_uuid of the source app.user_consent row. |

Keys: `PRIMARY KEY (user_key, policy_version, accepted_at)`

## dw.fact_device_day

Per device and business date: contact times, batch and record counts, rejects and battery low point.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `device_id` | bigint | not null |  | Phone (app.device) the row came from, taken from the token, never from the body. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `app_version` | text | null |  | App version as versionName+versionCode, e.g. 1.0.3+103. |
| `first_contact_at` | timestamp with time zone | null |  | UTC time of the device's first contact that day. |
| `last_contact_at` | timestamp with time zone | null |  | UTC time of the device's last contact that day. |
| `batches` | integer | not null |  | Upload batches received from the device that day. |
| `records` | integer | not null |  | Records received from the device that day. |
| `rejected` | integer | not null |  | Records rejected that day. |
| `quarantined` | integer | not null |  | Records quarantined that day. |
| `pending_rows_max` | integer | null |  | Highest pending-row count the device reported that day. |
| `battery_pct_min` | smallint | null |  | Lowest battery percentage the device reported that day. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |

Keys: `PRIMARY KEY (business_date, device_id)`

## dw.fact_device_integrity

One row is a phone's integrity and readiness state observed at a login or bundle download.

`owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `device_key` | bigint | not null |  | Phone (app.device.id; no device dimension table). |
| `user_key` | bigint | not null |  | User logged in on the phone (app.app_user.id; no user dimension table). |
| `business_date` | date | not null |  | Asia/Dhaka business date of the observation. |
| `observed_at` | timestamp with time zone | not null |  | UTC time of the login or bundle download the state was taken from. |
| `app_version` | text | null |  | App version on the phone. |
| `os_version` | text | null |  | Android version of the phone. |
| `device_model` | text | null |  | Manufacturer and model of the phone. |
| `battery_capacity_mah` | integer | null |  | Battery design capacity in mAh, when the phone reports it. |
| `mock_app_present` | boolean | null |  | True when a mock-location app was installed; null = not reported. |
| `developer_options` | boolean | null |  | True when developer options were on; null = not reported. |
| `rooted_hint` | boolean | null |  | True when any root hint was reported; false when the hint list was empty; null = unknown (older phone). |
| `play_integrity_verdict` | text | null |  | Play Integrity verdict of the observation; null when unavailable. |
| `attestation_level` | text | null |  | Key attestation security level of the phone's key. |
| `trust_level` | smallint | null |  | Server trust level of the phone at the observation (ordinal). |
| `time_skew_s` | integer | null |  | Phone clock minus server time, in seconds. |
| `clock_changed_count` | smallint | null |  | Number of manual clock changes reported since the previous observation. |
| `ready_bound` | boolean | null |  | Readiness: the phone is bound to the user. |
| `ready_bundle_next_day` | boolean | null |  | Readiness: the next day's bundle is on the phone. |
| `ready_printer_paired` | boolean | null |  | Readiness: a printer is paired. |
| `ready_test_print` | boolean | null |  | Readiness: a test print succeeded. |
| `ready_permissions` | boolean | null |  | Readiness: every required permission is granted. |
| `free_storage_mb` | integer | null |  | Free storage on the phone in MB. |
| `battery_pct` | smallint | null |  | Battery charge in percent at the observation. |
| `source_uuid` | uuid | not null |  | client_uuid of the source record (status report or bundle download). |
| `source` | text | not null |  | Origin of the row: native (this system) or an import. |
| `import_run_id` | bigint | null |  | Import run that loaded the row; null for native rows. |

Keys: `PRIMARY KEY (device_key, business_date, observed_at)`

## dw.fact_geo_fix

One row per location fix copied for analysis, with slot and satellite count.

`owner: worker | capture: SERVER | retention: event_fact | pii: personal` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `source_client_uuid` | uuid | not null |  | client_uuid of the record that produced the row. |
| `slot` | text | not null |  | Which fix of the record: fix or edit_fix. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `purpose` | text | not null |  | Purpose of the fix or photo; allowed values are listed under constraints. |
| `captured_at` | timestamp with time zone | not null |  | UTC instant of capture on the phone, from trusted time when an anchor exists. |
| `lat` | double precision | null | personal | Latitude of the user's location fix (WGS84 degrees). |
| `lng` | double precision | null | personal | Longitude of the user's location fix (WGS84 degrees). |
| `accuracy_m` | double precision | null | personal | Horizontal accuracy radius of the location fix in metres. |
| `provider` | text | not null |  | Android location provider of the fix: fused, gps, network, passive or unknown. |
| `is_mock` | boolean | not null |  | True when the location came from a mock location provider (never geo-valid). |
| `satellites_used` | smallint | null |  | Satellites used in the fix. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |

Keys: `PRIMARY KEY (source_client_uuid, slot, business_date)`

## dw.fact_memo

One row per memo with its money totals, for reports.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `memo_client_uuid` | uuid | not null |  | client_uuid of the memo the row belongs to. |
| `memo_no` | text | not null |  | Printed memo number <username>-<yyMMdd>-<seq>. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `status` | text | not null |  | Memo status: active, voided or superseded. |
| `committed_at` | timestamp with time zone | not null |  | UTC instant the row was committed. |
| `line_count` | smallint | not null |  | Number of memo lines. |
| `gross_mtk` | bigint | not null |  | Gross sales value before discounts, in milli-taka. |
| `offer_discount_mtk` | bigint | not null |  | Offer and free-goods discount in milli-taka (zero until the discount engine exists, docs/27). |
| `drp_discount_mtk` | bigint | not null |  | DRP (empty-pack slide) discount in milli-taka. |
| `qc_deduction_mtk` | bigint | not null |  | QC settlement deducted on the memo, in milli-taka. |
| `net_mtk` | bigint | not null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `paid_mtk` | bigint | not null |  | Amount paid in cash at the memo, in milli-taka. |
| `due_mtk` | bigint | not null |  | Amount left on credit (due) in milli-taka. |
| `is_credit` | boolean | not null |  | True when the memo leaves a due. |
| `captured_offline` | boolean | not null |  | True when the phone had no connection at capture. |
| `received_at` | timestamp with time zone | not null |  | UTC instant the server received the row. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `geo_key` | bigint | null |  | dim_geo_version key valid on the business date (dw.geo_key_on). |
| `outlet_key` | bigint | null |  | dim_outlet_version key valid on the business date (dw.outlet_key_on). |
| `user_key` | bigint | null |  | dim_user_version key valid on the business date (dw.user_key_on). |

Keys: `PRIMARY KEY (memo_client_uuid, business_date)`

## dw.fact_visit

One row per visit with device and server verdicts, distance and void flag.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · partitioned table

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | not null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `visit_client_uuid` | uuid | not null |  | client_uuid of the visit the row belongs to. |
| `user_id` | bigint | not null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `outlet_id` | bigint | not null |  | Outlet (app.outlet). |
| `visit_kind` | text | not null |  | Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry (route tables: daily, 3f or 2f). |
| `opened_at` | timestamp with time zone | not null |  | UTC time the visit was opened. |
| `ended_at` | timestamp with time zone | null |  | UTC instant the activity ended. |
| `outcome_code` | text | null |  | Outcome of the visit or check; allowed values are listed under constraints. |
| `call_declined` | boolean | null |  | True when the retailer declined the call. |
| `device_verdict` | text | not null |  | Phone's geo verdict for the visit. |
| `server_verdict` | text | null |  | Server's re-check verdict for the visit. |
| `geo_action` | text | not null |  | Action taken: sale_allowed, force_sale or blocked. |
| `distance_m` | double precision | null |  | Distance in metres between the fix and the outlet. |
| `is_mock` | boolean | null |  | True when the location came from a mock location provider (never geo-valid). |
| `planned` | boolean | not null |  | True when the route or outlet was planned for the business date. |
| `voided` | boolean | not null |  | True when the visit was voided. |
| `last_event_id` | bigint | not null |  | Highest app.domain_event id folded into this row by the projector (replays are ignored). |
| `updated_at` | timestamp with time zone | not null |  | UTC instant of the last update. |
| `geo_key` | bigint | null |  | dim_geo_version key valid on the business date (dw.geo_key_on). |
| `outlet_key` | bigint | null |  | dim_outlet_version key valid on the business date (dw.outlet_key_on). |
| `user_key` | bigint | null |  | dim_user_version key valid on the business date (dw.user_key_on). |

Keys: `PRIMARY KEY (visit_client_uuid, business_date)`

## dw.v_attendance

Stable view: per user and business date, check-in and check-out times, hours in field and mock-location flags (no coordinates or accuracy).

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `role` | text | null |  | User role (contract Role). |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `check_in_at` | timestamp with time zone | null |  | UTC instant of the day's check-in. |
| `check_out_at` | timestamp with time zone | null |  | UTC instant of the day's check-out. |
| `hours_in_field` | numeric | null |  | Hours between check-in and check-out (2 decimals); null until both exist. |
| `check_in_is_mock` | boolean | null |  | True when the check-in fix came from a mock location provider. |
| `check_out_is_mock` | boolean | null |  | True when the check-out fix came from a mock location provider. |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |

## dw.v_collections

Stable view: outlet-days with new credit or dues collected (credit and collection movements).

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `outlet_code` | text | null |  | Outlet code. |
| `outlet_name` | text | null |  | Outlet name. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `sales_net_mtk` | bigint | null |  | Net value of the outlet's active memos that day, in milli-taka. |
| `new_due_mtk` | bigint | null |  | New credit (due) raised by that day's memos, in milli-taka. |
| `dues_collected_mtk` | bigint | null |  | Dues collected against earlier credit memos, in milli-taka. |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |

## dw.v_daily_outlet

Stable view: per outlet and business date, visit, geo validity, sales and dues, with the outlet's classification.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `outlet_code` | text | null |  | Outlet code. |
| `outlet_name` | text | null |  | Outlet name. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `cluster_id` | bigint | null |  | Outlet cluster (market group) inside the zone. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `channel` | text | null |  | Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa. |
| `sub_channel_id` | bigint | null |  | Outlet sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural. |
| `outlet_kind` | text | null |  | retail or wholesale. |
| `location_confirmed` | boolean | null |  | True when the outlet pin is confirmed. |
| `visited` | boolean | null |  | True when the outlet had an SR visit that was not abandoned. |
| `geo_valid` | boolean | null |  | True when a visit was in range by the server's re-check. |
| `active_memo_count` | integer | null |  | Number of active (not voided or superseded) memos with at least one line. |
| `sold_qty_base` | bigint | null |  | Quantity sold in base units. |
| `net_mtk` | bigint | null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `due_mtk` | bigint | null |  | Amount left on credit (due) in milli-taka. |
| `dues_collected_mtk` | bigint | null |  | Dues collected against earlier credit memos, in milli-taka. |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |

## dw.v_daily_route

Stable view (contract for BI and other products): KPIs, money and day state per route and business date, with its geography.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `route_code` | text | null |  | Route code. |
| `route_name` | text | null |  | Route name. |
| `visit_kind` | text | null |  | Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry (route tables: daily, 3f or 2f). |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `zone_name` | text | null |  | Zone name. |
| `territory_id` | bigint | null |  | Territory (app.territory). |
| `territory_name` | text | null |  | Territory name. |
| `division_id` | bigint | null |  | Division (app.division). |
| `division_name` | text | null |  | Division name. |
| `wing_id` | bigint | null |  | Wing (app.wing). |
| `wing_name` | text | null |  | Wing name. |
| `planned` | boolean | null |  | True when the route or outlet was planned for the business date. |
| `exception_approved` | boolean | null |  | True when an approved day exception removes the route-day from the target. |
| `day_state` | text | null |  | Route-day state (contract DayState). |
| `logged_in_at` | timestamp with time zone | null |  | UTC instant the route-day entered logged_in. |
| `sales_submitted_at` | timestamp with time zone | null |  | UTC instant Sales Submit took effect. |
| `final_submitted_at` | timestamp with time zone | null |  | UTC instant the zone-day was final-submitted. |
| `target_outlets` | integer | null |  | Outlets planned for the route-day, frozen at the first bundle of the day (strike-rate denominator). |
| `visited_outlets` | integer | null |  | Distinct outlets with an SR visit not closed abandoned (docs/24 s12.4). |
| `successful_calls` | integer | null |  | Distinct outlets with an active memo with at least one line. |
| `strike_rate_pct` | numeric | null |  | successful_calls / target_outlets x 100, 2 decimals; null when target_outlets is 0. |
| `visits` | integer | null |  | Number of visits. |
| `geo_valid_visits` | integer | null |  | Visits in range by the server's re-check. |
| `geo_valid_pct` | numeric | null |  | geo_valid_visits / visits x 100; null when there were no visits. |
| `force_sale_visits` | integer | null |  | Visits whose geo action was force_sale. |
| `mock_visits` | integer | null |  | Visits with a mocked location fix. |
| `suspicious_visits` | integer | null |  | Visits of user-days whose risk score reached cfg.geo.suspicious_score_threshold. |
| `active_memo_count` | integer | null |  | Number of active (not voided or superseded) memos with at least one line. |
| `gross_mtk` | bigint | null |  | Gross sales value before discounts, in milli-taka. |
| `offer_discount_mtk` | bigint | null |  | Offer and free-goods discount in milli-taka (zero until the discount engine exists, docs/27). |
| `drp_discount_mtk` | bigint | null |  | DRP (empty-pack slide) discount in milli-taka. |
| `qc_deduction_mtk` | bigint | null |  | QC settlement deducted on the memo, in milli-taka. |
| `net_mtk` | bigint | null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `paid_mtk` | bigint | null |  | Amount paid in cash at the memo, in milli-taka. |
| `due_mtk` | bigint | null |  | Amount left on credit (due) in milli-taka. |
| `dues_collected_mtk` | bigint | null |  | Dues collected against earlier credit memos, in milli-taka. |
| `late_rows_after_final` | integer | null |  | Rows received after the zone-day was final-submitted (accepted and flagged). |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |

## dw.v_daily_sku

Stable view: per route, SKU and business date, quantities sold, free, issued and returned and the gross value.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `route_id` | bigint | null |  | Route (app.route) being worked. |
| `route_code` | text | null |  | Route code. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `territory_id` | bigint | null |  | Territory (app.territory). |
| `sku_id` | bigint | null |  | SKU (app.sku). |
| `sku_code` | text | null |  | SKU code. |
| `short_name` | text | null |  | Printed short name of the SKU. |
| `base_unit` | text | null |  | Base unit of the SKU: stick, piece or dozen. |
| `base_per_pack` | integer | null |  | Base units per pack. |
| `brand_id` | bigint | null |  | Product brand (app.product_node of level brand). |
| `brand_name` | text | null |  | Brand name. |
| `category_code` | text | null |  | Product category: cigarette, bidi, lighter or match. |
| `sold_qty_base` | bigint | null |  | Quantity sold in base units. |
| `free_qty_base` | bigint | null |  | Free quantity given, in base units. |
| `issued_qty_base` | bigint | null |  | Quantity issued to the route's stock, in base units. |
| `returned_qty_base` | bigint | null |  | Quantity returned from the route's stock, in base units. |
| `gross_mtk` | bigint | null |  | Gross sales value before discounts, in milli-taka. |
| `memo_count` | integer | null |  | Active memos containing the SKU. |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |

## dw.v_daily_sr

Stable view: per field user and business date, SR calls, successful calls, geo validity and memo money from the dw facts (active memos with lines); a user-day with neither an SR call nor such a memo has no row.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `route_ids` | bigint[] | null |  | Routes the user called on that day. |
| `visits` | bigint | null |  | Number of visits. |
| `visited_outlets` | bigint | null |  | Distinct outlets visited in SR calls not abandoned or declined. |
| `successful_calls` | bigint | null |  | Distinct outlets with an active memo with at least one line. |
| `geo_valid_visits` | bigint | null |  | SR calls in range by the server's re-check. |
| `geo_valid_pct` | numeric | null |  | geo_valid_visits / visits x 100; null when there were no visits. |
| `force_sale_visits` | bigint | null |  | SR calls whose geo action was force_sale. |
| `mock_visits` | bigint | null |  | SR calls with a mocked fix. |
| `active_memo_count` | bigint | null |  | Number of active (not voided or superseded) memos with at least one line. |
| `gross_mtk` | bigint | null |  | Gross sales value before discounts, in milli-taka. |
| `net_mtk` | bigint | null |  | Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa). |
| `due_mtk` | bigint | null |  | Amount left on credit (due) in milli-taka. |
| `memos_captured_offline` | bigint | null |  | Memos captured while the phone was offline. |
| `first_visit_at` | timestamp with time zone | null |  | UTC instant the first call opened. |
| `last_visit_end_at` | timestamp with time zone | null |  | UTC instant the last call ended. |

## dw.v_geo_integrity

Stable view: per user and business date, geo-validation outcomes of visits and mock-location evidence of fixes.

`owner: worker | capture: SERVER | retention: event_fact | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `business_date` | date | null |  | Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it. |
| `user_id` | bigint | null |  | User (app.app_user); for device records the capturing user from the token. |
| `visits` | bigint | null |  | Number of visits. |
| `server_in_range` | bigint | null |  | Visits in range by the server's re-check. |
| `geo_valid_pct` | numeric | null |  | server_in_range / visits x 100; null when there were no visits. |
| `server_out_of_range` | bigint | null |  | Visits out of range by the server's re-check. |
| `no_fix` | bigint | null |  | Visits where the phone got no location fix. |
| `accuracy_too_low` | bigint | null |  | Visits whose fix was less accurate than the allowed maximum. |
| `mocked_visits` | bigint | null |  | Visits with a mocked fix. |
| `force_sale_visits` | bigint | null |  | Visits whose geo action was force_sale. |
| `blocked_visits` | bigint | null |  | Visits blocked by the mock-location policy. |
| `device_server_mismatch` | bigint | null |  | Visits where the server's verdict differs from the phone's. |
| `fixes` | bigint | null |  | Location fixes received for the user-day. |
| `mock_fixes` | bigint | null |  | Fixes flagged as mock locations. |
| `avg_accuracy_m` | numeric | null |  | Average fix accuracy in metres (1 decimal). |

## dw.v_outlet_masked

Stable view: outlets without owner name, address or national ids; the phone masked to 01*****NNN (D-107).

`owner: backend:masterdata | capture: ONLINE | retention: master | pii: none` · view

| Column | Type | Null | PII | Description |
|---|---|---|---|---|
| `outlet_id` | bigint | null |  | Outlet (app.outlet). |
| `outlet_code` | text | null |  | Outlet code. |
| `outlet_name` | text | null |  | Shop name (English). |
| `outlet_name_bn` | text | null |  | Shop name in Bangla. |
| `zone_id` | bigint | null |  | Zone (app.zone). |
| `route_id` | bigint | null |  | Route (app.route). |
| `cluster_id` | bigint | null |  | Cluster (market) of the outlet. |
| `channel` | text | null |  | Channel (GT, DCC, Astha, RCC, MT, HoReCa). |
| `sub_channel_id` | bigint | null |  | Sub-channel (app.sub_channel). |
| `geo_class` | text | null |  | Geographic class of the outlet. |
| `outlet_kind` | text | null |  | retail or wholesale. |
| `price_type` | text | null |  | Price type the outlet buys at. |
| `status` | text | null |  | active, closed, merged or archived. |
| `location_confirmed` | boolean | null |  | True when the master location is confirmed. |
| `contact_number_masked` | text | null |  | Phone masked as 01*****NNN (last three digits); null when not a Bangladeshi mobile number. |
| `updated_at` | timestamp with time zone | null |  | UTC instant of the last update. |
