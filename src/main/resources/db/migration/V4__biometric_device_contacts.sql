DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

CREATE TABLE intra.biometric_device_contacts (
  serial_number varchar(64) PRIMARY KEY,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  last_endpoint varchar(48) NOT NULL,
  contact_count bigint NOT NULL DEFAULT 1
);

GRANT SELECT, INSERT, UPDATE ON intra.biometric_device_contacts TO salomon_intra_app;
