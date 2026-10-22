# Status: mdm-web (web half of lane mdm), D-MDM-01

Branch `lane/mdm-web` (based on lane/mdm bc463568: contract 1.4.0). Touches `web/` only.

## Done
- Menu entry "MDM" (group admin; ADMIN, SUPERADMIN), route gate `/admin/mdm` (proxy: ADMIN_PATH_ROLES) and a server gate in every page (`mdmLevel`: SUPERADMIN w; ADMIN follows the optional `modules.mdm` claim, w until the claim exists; r = lists only, no action buttons).
- Pages: `/admin/mdm` (device list, filters status/flavour/flag/policy/q), `/admin/mdm/devices/[id]` (facts from the latest report, flags, actions lock/unlock/message/locate/reboot/factory reset with mandatory reason and client command_id, pending_approval approve/reject for a different admin, policy assignment, commands, activity log), `/admin/mdm/policies` + `/new` + `/[id]` (allowed apps, 7x48 half-hour block grid -> MdmBlockWindow[], kiosk, location sampling, optimistic version), `/admin/mdm/enrolment` (QR once, list, revoke), `/admin/mdm/alerts`, `/admin/mdm/activity`.
- Whitelisted ops `mdm.*` in `lib/admin/ops.ts`; strings in `lib/i18n/messages-mdm.ts` (bn + en pairs); mock endpoints `web/mock/mdm.ts`.
- Tests: `tests/mdm.test.ts` (18), `e2e/mdm.spec.ts` (10); `tests/roles-menu.test.ts` expected list gained `/admin/mdm`.

## Notes for the backend half
- Contract `Search` parameter is named `q`, not `search` (the list pages send `q`).
- Reason travels as body member `reason` (ops table); a policy update carries `version` in the body; command `created_by`/`approved_by` should be the username (the UI hides Approve when `created_by` equals the signed-in username; the API must enforce it).
- The package picker uses a static suggestion list (no catalogue endpoint in the contract); free text is validated with the PackageName pattern.
- Read-only (`r`) cannot be exercised end to end until a `modules` claim exists in the session; covered by unit tests.
