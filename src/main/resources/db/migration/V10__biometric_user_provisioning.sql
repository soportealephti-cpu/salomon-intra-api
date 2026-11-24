DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- The employee directory remains authoritative; this table only tracks
-- explicit one-person, one-device ADMS provisioning requests.
CREATE TABLE intra.biometric_user_provision_commands (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
  device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
  device_user_id varchar(64) NOT NULL,
  requested_name varchar(160) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','sent','confirmed','failed','expired')),
  result_code integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  completed_at timestamptz,
  CONSTRAINT provision_pin_format CHECK (device_user_id ~ '^[0-9]{1,15}$')
);
CREATE INDEX biometric_user_provision_by_device
  ON intra.biometric_user_provision_commands(device_id, status, created_at);
CREATE UNIQUE INDEX biometric_user_provision_one_open
  ON intra.biometric_user_provision_commands(employee_id,device_id)
  WHERE status IN ('pending','sent');
GRANT SELECT,INSERT,UPDATE ON intra.biometric_user_provision_commands TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.biometric_user_provision_commands_id_seq TO salomon_intra_app;
