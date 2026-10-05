# Request: a reason member on every master-data create (lane web-admin, 2026-10-05)

**Need.** The admin portal demands a reason on every write (playbook s5, N-011 acceptance: "writes an audit row with the
reason"). Updates carry it (`change_reason` on `ClusterPatch` and the other `*Patch` schemas). Creates do not:
`ClusterWrite` is a closed object (`additionalProperties: false`) with only `zone_id`, `name`, `cluster_type`, so the
reason typed by the user on a create cannot be sent and the audit row has `reason: null`.

**Exact shape.** Add to every master-data create schema (at least `ClusterWrite`; also the geography, route, SKU, outlet,
user, holiday create bodies that lack it):

```yaml
change_reason: { oneOf: [ { $ref: '#/components/schemas/ChangeReason' }, { type: 'null' } ] }
```

Optional, additive (docs/24 s3.7), same semantics as on the `*Patch` schemas: stored in `audit_log.reason`.

**Local stub.** `web/src/app/admin/_entities/clusters.ts` sets `reasonOnCreate: null` (`// REQUEST:` marker). The reason is
still required in the form and by the BFF; it is just not forwarded. When the contract adds the member, set
`reasonOnCreate: "change_reason"` and regenerate the client.
