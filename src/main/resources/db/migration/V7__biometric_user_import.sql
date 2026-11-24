DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- Read-only user-sync command queue: no actions to modify the biometric device.
CREATE TABLE intra.biometric_user_sync_commands (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id bigint NOT NULL REFERENCES intra.biometric_devices(id) ON DELETE CASCADE,
  status varchar(20) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'sent', 'done', 'failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  completed_at timestamptz,
  result_code integer,
  CONSTRAINT biometric_user_sync_device_id_positive CHECK (device_id > 0)
);
CREATE INDEX biometric_user_sync_pending_idx
  ON intra.biometric_user_sync_commands (device_id, status, created_at);

GRANT SELECT, INSERT, UPDATE ON intra.biometric_user_sync_commands TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.biometric_user_sync_commands_id_seq TO salomon_intra_app;
