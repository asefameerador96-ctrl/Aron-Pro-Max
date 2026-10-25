# Contract lane status

## HANDOVER (2026-10-24, contract 1.9.0 batch, branch `lane/lead-contract-1.9`)

- **Shipped in 1.9.0** (rows 1 to 5 of `docs/requests/contract-v1.9-queue.md`, D-CONTRACT-1-9-01): `MemoView.memo_no` is `MemoNo`; `MemoView.route_id` nullable; `getFlaggedMemos` names NATIONAL, TOP, ANALYST (description only); `SyncTotalsResponse.digest` (optional until the server sends it; `SalesSubmitDigest`, `DigestTypeCounts`, `DigestDeviceCounts`, `DigestLoginCounts`, `RejectedRecordView`) as backend-core #18 wrote it; `RiskSignalCode` `CHECKOUT_WITHOUT_SUBMIT`. Slices and web `openapi.d.ts` regenerated; WireDtos and PayloadShapes regenerated, unchanged; `ContractCatalogues.kt` mirrored; docs/24 s4.12, s11.4, s14a R23.
- **Breaking, approved:** the two widened `MemoView` response members (oasdiff), by the bump plus the changed queue file.
- **Waiting on other lanes:** backend-core wires the left join, the digest and the check-out signal (not yet pushed; `lane/backend-core` 9ce9270 holds the shapes file only), then a batch makes `digest` required; db adds `CHECKOUT_WITHOUT_SUBMIT` to `app.risk_signal_code_check` and the weight 20; android shows the digest and rejected list; web reads a nullable `route_id`.
- **In 1.10** (`docs/requests/contract-v1.10-queue.md`): row 1, mdm packages catalogue endpoint and `MdmPolicy`/`MdmPolicyWrite.work_hours_allowlist` (moved from v1.9 row 6; shape file from mdm #3 pending).
