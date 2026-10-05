# Request (db → lead/contract): integer scope_id for the role and geo_class config scopes

The contract gives `ConfigValue.scope_id` and `ConfigChangeItem.scope_id` as integers (0 for global), but the
`role` and `geo_class` scopes have no numeric id. The schema encodes them as the ordinal of two lookup tables:

| scope_type | scope_id |
|---|---|
| role | `app.role_def.ordinal`: SR 1, AMO 2, TSO 3, DMO 4, WM 5, TOP 6, ANALYST 7, SUPPORT 8, ADMIN 9, SUPERADMIN 10 |
| geo_class | `app.geo_class_def.ordinal`: Hill 1, Urban 2, SemiUrban 3, Rural 4 |

**Asked:** write this encoding into the contract descriptions of `scope_id` (or tell me the encoding to use).
