# Contract 1.9 queue row 6: MDM shapes (lane mdm, session 3, 2026-10-24)

Ruling D-NIGHT-MDM-ALLOWLIST-01. Two parts, both additive. Written by lane mdm for the lead's 1.9 batch; lane mdm does not
edit `contract/` (D-CONTRACT-BATCH-01). Part A is already served by the backend (optional member, absent when unset; see
"Interim" below); part B is not built until the batch lands.

## A. `work_hours_allowlist` on the policy (served from mdm candidate of 2026-10-24)

New schemas (`components/schemas`):

```yaml
    MdmAllowlistSchedule:
      type: object
      description: >-
        Working hours of the allow-list, Dhaka time, same day (end after start). `calendar` blocks on every working day of
        the bundle calendar (holidays stay free; a phone without a calendar blocks nothing); `window` blocks on the listed
        ISO weekdays.
      required: [mode, start, end]
      additionalProperties: false
      properties:
        mode: { type: string, enum: [calendar, window] }
        days: { type: array, maxItems: 7, uniqueItems: true, items: { type: integer, minimum: 1, maximum: 7, description: 'ISO weekday, 1 = Monday.' }, description: 'Required (at least one) with mode window; empty with mode calendar.' }
        start: { type: string, pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$' }
        end: { type: string, pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$' }
    MdmWorkHoursAllowlist:
      type: object
      description: >-
        D-NIGHT-MDM-ALLOWLIST-01. While enabled and inside the schedule (from start + grace_min to end), the device owner
        suspends every launcher-visible app that is not in `packages`, the policy's `allowed_packages` or the phone's
        protected set (the ARON apps, diallers and call handlers, default SMS, launcher, keyboards, Settings, System UI,
        installer, permission controller, emergency). Released outside the schedule. No effect on a phone that is not
        device owner.
      required: [enabled, packages, schedule]
      additionalProperties: false
      properties:
        enabled: { type: boolean }
        packages: { type: array, maxItems: 50, uniqueItems: true, items: { $ref: '#/components/schemas/PackageName' }, description: The allowed apps (stored sorted). }
        schedule: { $ref: '#/components/schemas/MdmAllowlistSchedule' }
        grace_min: { type: integer, minimum: 0, maximum: 30, default: 5, description: Minutes after the start before apps are suspended; must be shorter than the working hours. }
```

Members (both optional, not in `required`):

```yaml
    MdmPolicyWrite:
      properties:
        work_hours_allowlist: { $ref: '#/components/schemas/MdmWorkHoursAllowlist' }
    MdmPolicy:
      properties:
        work_hours_allowlist: { $ref: '#/components/schemas/MdmWorkHoursAllowlist' }
```

`MdmPolicy.work_hours_allowlist` is **absent** (not null) when the policy has none, so no `null` in the type. The
`apply_policy` command's `params.policy` carries the same object (params are free-form today; no schema change).

Validation the server applies (400 `ERR_VALIDATION`, pointer in brackets): package syntax and duplicates
(`/work_hours_allowlist/packages[/i]`), at most 50; `mode` (`/schedule/mode`); `days` required for `window`, empty for
`calendar` (`/schedule/days`); `start`/`end` syntax, end after start (`/schedule/end`); `grace_min` 0..30 and below the
span (`/grace_min`). Also new from this slice: a block window's `packages` may not name an ARON app
(`com.aktcl.aron.*`) or a known dialler/telephony/Settings/System UI package (`/block_windows/{i}/packages`). This
tightens 1.8 validation: a policy stored before it with such a window is refused on its next save until the window is
edited (the phone never suspended those packages anyway). Description text for `MdmBlockWindow.packages` in the batch:
"may not name an ARON app or a dialler, telephony, Settings or System UI package".

Semantics to state in the descriptions: `PUT` replaces the whole policy, so a writer that leaves the member out removes
the allow-list (the portal always sends it once a policy has one). Working hours end at 23:59 at the latest (no 24:00).

oasdiff: two optional request and response members and two new schemas: non-breaking. Mirrors: `web/src/contract/openapi.d.ts`
(regenerate; then drop the local `MdmWorkHoursAllowlist`/`MdmPolicyFull` types in `web/src/lib/admin/mdm.ts` and the
`Policy &` widening in `web/mock/mdm.ts`), backend `MdmModels.kt` (already carries them), conformance fixture
`mdmPolicy(...)` in `ContractConformanceTest` (add the member to cover it: `{"enabled":true,"packages":["com.whatsapp"],
"schedule":{"mode":"calendar","days":[],"start":"09:00","end":"18:00"},"grace_min":5}`).

**Interim (until the batch):** the backend accepts and returns the member as above; a policy without it is the 1.8 shape
byte for byte (`@EncodeDefault(NEVER)`), so the conformance suite and every 1.8 reader are unaffected. The lead may
instead ask lane mdm to hold the member behind the batch; then the web card stays and saves are refused until 1.9.

## B. Packages catalogue (the picker's list; not built yet)

Phone to server: the DPC sends its launcher-visible package list only when it changed (hash compared locally), checked at
app start and at each sync run; no new receiver, never on a timer.

```yaml
  /v1/devices/me/packages:
    put:
      tags: [devices]
      operationId: putDevicePackages
      summary: Replace this phone's launcher-visible package list (sent only when it changed).
      description: Authenticated by X-Device-Proof. Idempotent by `list_sha256`; a repeat of the stored hash is 200 with `changed=false`.
      security:
        - bearerAuth: []
        - {}
      parameters:
        - $ref: '#/components/parameters/XDeviceIdRequired'
        - $ref: '#/components/parameters/XDeviceProof'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/DevicePackageList' }
      responses:
        '200':
          description: Stored (or unchanged).
          content:
            application/json:
              schema: { $ref: '#/components/schemas/DevicePackageListAck' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
  /v1/admin/mdm/packages:
    get:
      tags: [admin-mdm]
      operationId: listMdmPackages
      summary: Fleet catalogue of launcher-visible apps for the allow-list picker, most common first.
      description: SUPERADMIN, or ADMIN with the mdm module (r or w). Not reach-scoped (the MDM section is national).
      parameters:
        - { name: q, in: query, required: false, schema: { type: string, maxLength: 80 }, description: Case-insensitive match on package or label. }
        - { name: device_id, in: query, required: false, schema: { $ref: '#/components/schemas/Id' }, description: Only this phone's apps. }
        - $ref: '#/components/parameters/Limit'
        - $ref: '#/components/parameters/Cursor'
      responses:
        '200':
          description: Page of packages.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/MdmPackagePage' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
```

```yaml
    DevicePackage:
      type: object
      required: [package, label, system]
      additionalProperties: false
      properties:
        package: { $ref: '#/components/schemas/PackageName' }
        label: { type: string, maxLength: 80, description: Launcher label in the phone's language, trimmed. }
        system: { type: boolean, description: FLAG_SYSTEM (updated system apps included). }
        version_code: { type: [integer, 'null'], format: int64, minimum: 0 }
        icon_sha256: { type: [string, 'null'], pattern: '^[0-9a-f]{64}$', description: Hash of the 48 px launcher icon PNG; the icon itself is not uploaded in 1.9. }
    DevicePackageList:
      type: object
      required: [packages, list_sha256, captured_at]
      additionalProperties: false
      properties:
        packages: { type: array, maxItems: 400, items: { $ref: '#/components/schemas/DevicePackage' } }
        list_sha256: { type: string, pattern: '^[0-9a-f]{64}$', description: SHA-256 of the sorted package ids joined by newlines. }
        captured_at: { $ref: '#/components/schemas/Timestamp' }
    DevicePackageListAck:
      type: object
      required: [changed, list_sha256]
      additionalProperties: false
      properties:
        changed: { type: boolean }
        list_sha256: { type: string, pattern: '^[0-9a-f]{64}$' }
    MdmPackage:
      type: object
      required: [package, label, system, device_count, last_seen_at]
      additionalProperties: false
      properties:
        package: { $ref: '#/components/schemas/PackageName' }
        label: { type: string, maxLength: 80, description: The most common label across phones. }
        system: { type: boolean }
        device_count: { type: integer, minimum: 0 }
        icon_sha256: { type: [string, 'null'], pattern: '^[0-9a-f]{64}$' }
        last_seen_at: { $ref: '#/components/schemas/Timestamp' }
        protected: { type: boolean, description: True when no MDM rule may suspend it (ARON apps, diallers, Settings, ...); the picker shows it as always allowed. }
    MdmPackagePage:
      type: object
      required: [items, next_cursor]
      additionalProperties: false
      properties:
        items: { type: array, items: { $ref: '#/components/schemas/MdmPackage' } }
        next_cursor: { type: [string, 'null'] }
```

Data cost: about 60 apps x 80 bytes = 5 KB raw, under 2 KB gzipped, only when the list changes. Storage needs a db
table (`app.device_package` keyed by device and package, replaced per upload); lane mdm writes
`docs/requests/db-mdm-device-packages.md` when the batch is scheduled. Until B ships the picker stays the typed list with
presets (`ALLOWLIST_PRESETS`).
