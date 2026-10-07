# Request: a reason member on master-data writes that lack one (lane web-admin, updated 2026-10-07)

**Need.** The admin portal demands a reason on every write (playbook s5). Where the contract has a reason member the reason is
stored in the audit row (`change_reason`, or `reason` on assignments and holidays). These write schemas have none, so the
reason the user types is required by the form and the BFF but cannot be forwarded (the schemas are closed objects):

| Schema (operation) | Missing |
|---|---|
| `ClusterWrite` (createCluster) | `change_reason` |
| `RouteWrite` (createRoute) | `change_reason` |
| `UserWrite` (createUser) | `change_reason` |
| `SkuWrite` (createSku) | `change_reason` |
| `ProductNodeWrite` (createProductNode) and `ProductNodePatch` (updateProductNode) | `change_reason` on both |

**Exact shape.** Add, optional and additive (docs/24 s3.7), same semantics as on the other `*Patch` schemas, stored in `audit_log.reason`:

```yaml
change_reason: { oneOf: [ { $ref: '#/components/schemas/ChangeReason' }, { type: 'null' } ] }
```

**Local stub.** `reasonOnCreate: null` / `reasonOnUpdate: null` in `web/src/app/admin/_entities/*.ts` (marked `// REQUEST:`). The
create form shows "this record type cannot save the reason yet" (`admin.reason.not_stored`). When the contract adds the member, set
`reasonOnCreate: "change_reason"` and regenerate the client.

**State: open, routed by the lead.**
