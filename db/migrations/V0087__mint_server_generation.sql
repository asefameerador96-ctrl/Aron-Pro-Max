-- V0087 (DD-02, FM-20; docs/24 s4.8, F-SYS-089): after a point-in-time restore, or a failover that may have lost
-- acknowledged writes, the new lineage must be minted BEFORE the API serves again. Until V0008 nothing but the first
-- migration ever wrote app.server_generation: a restored server kept the old generation, phones saw no change, kept their
-- acknowledged rows as safe and purged them three days later, although the restore had dropped them (the silent-loss
-- path). One function, one statement, run by the restore runbook (docs/runbooks/pitr-restore.md), the failover runbook
-- and the drill (infra/scripts/mint-generation.sh): closes the current generation and opens the new one.

CREATE FUNCTION app.mint_server_generation(p_kind text, p_lost_after_utc timestamptz, p_restore_point_utc timestamptz DEFAULT NULL, p_note text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE g uuid := gen_random_uuid();
BEGIN
  IF p_kind NOT IN ('failover', 'pitr_restore') THEN
    RAISE EXCEPTION 'mint_server_generation: kind must be failover or pitr_restore, not %', p_kind;
  END IF;
  IF p_lost_after_utc IS NULL THEN
    RAISE EXCEPTION 'mint_server_generation: lost_after_utc is required (the instant after which acknowledged writes may be lost)';
  END IF;
  IF p_restore_point_utc IS NOT NULL AND p_restore_point_utc < p_lost_after_utc THEN
    RAISE EXCEPTION 'mint_server_generation: restore_point_utc % is before lost_after_utc %', p_restore_point_utc, p_lost_after_utc;
  END IF;
  UPDATE app.server_generation SET is_current = false WHERE is_current;
  INSERT INTO app.server_generation (generation, kind, started_at, restore_point_utc, lost_after_utc, is_current, note)
  VALUES (g, p_kind, now(), p_restore_point_utc, p_lost_after_utc, true, left(p_note, 500));
  RETURN g;
END $$;
COMMENT ON FUNCTION app.mint_server_generation(text, timestamptz, timestamptz, text) IS
  'DD-02: closes the current server generation and opens a new one (failover or pitr_restore) so phones re-send what the lost lineage acknowledged (F-SYS-089 resync); returns the new generation id. Run before the API serves again after a restore.';

-- The one who restores runs it as the admin login; the api never mints (it only reads the current row).
REVOKE EXECUTE ON FUNCTION app.mint_server_generation(text, timestamptz, timestamptz, text) FROM PUBLIC;
