DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

CREATE TABLE intra.biometric_devices (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  serial_number varchar(64) NOT NULL UNIQUE,
  name varchar(120) NOT NULL,
  model varchar(100) NOT NULL,
  location varchar(160),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT biometric_device_serial_check CHECK (serial_number ~ '^[A-Za-z0-9_-]{4,64}$')
);

GRANT SELECT, INSERT, UPDATE ON intra.biometric_devices TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.biometric_devices_id_seq TO salomon_intra_app;
