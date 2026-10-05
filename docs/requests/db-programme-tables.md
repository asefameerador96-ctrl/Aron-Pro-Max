# Request (db → lead): programme record tables (Diamond League redemption, gift photo, Astha)

v1b (V0007, V0008) was written against `contract/openapi.yaml` at merge caa8d76 and again at the end of N-006;
neither had the Diamond League redemption, gift photo or Astha programme record types, so v1b has no tables
for them. `app.outlet_programme` (programme eligibility dots, `BundleOutlet.programme_flags`) exists.

**Asked:** tell the db lane when those record types are in the contract; a follow-up migration adds their tables
with the same envelope (client_uuid UNIQUE, business_date, captured_at timestamptz, user/device from the token).
