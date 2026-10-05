# Request (db → lead): ingest_registry is hash-partitioned on client_uuid, not range-partitioned on received_at

docs/24 s12.1 says the large tables are range-partitioned by month "on business_date (or received_at for the
registry)". A partitioned table's primary key must contain the partition column, so a registry partitioned on
received_at could only have the key (client_uuid, received_at): the same client_uuid could then be registered twice
on different days, which breaks the single most important rule (s3.3, CLAUDE.md "idempotent sync").

**Decision taken (V0008):** `app.ingest_registry` is HASH-partitioned on client_uuid (16 partitions) with
`PRIMARY KEY (client_uuid)`; retention (`cfg.retention.ingest_registry_days`) is a worker DELETE using the
`received_at` index. **Asked:** correct the sentence in docs/24 s12.1.

Related: on the business_date-partitioned tables (visit, memo, memo_line, geo_fix) the unique key is
`(client_uuid, business_date)`; a BEFORE INSERT trigger (`app.client_uuid_once`) refuses the same client_uuid under
another business date, and the registry (written first in the same transaction by the ingest path) is the
concurrency-safe global guard.
