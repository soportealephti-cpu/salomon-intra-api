DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- Store device punches without requiring an existing employee mapping.
-- Device local time is interpreted in the company's Lima time zone, and the
-- original local date/time is retained to make troubleshooting possible.
CREATE TABLE intra.biometric_attendance_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
  device_user_id varchar(64) NOT NULL,
  device_punched_at timestamp without time zone NOT NULL,
  punched_at timestamptz NOT NULL,
  attendance_status varchar(32) NOT NULL DEFAULT '',
  verify_mode varchar(32) NOT NULL DEFAULT '',
  work_code varchar(64) NOT NULL DEFAULT '',
  received_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT biometric_attendance_pin_nonempty CHECK (length(trim(device_user_id)) > 0),
  CONSTRAINT biometric_attendance_event_unique
    UNIQUE (device_id, device_user_id, device_punched_at,
            attendance_status, verify_mode, work_code)
);
CREATE INDEX biometric_attendance_events_by_time
  ON intra.biometric_attendance_events (punched_at DESC);
CREATE INDEX biometric_attendance_events_by_person
  ON intra.biometric_attendance_events (device_id, device_user_id, punched_at DESC);

GRANT SELECT, INSERT ON intra.biometric_attendance_events TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.biometric_attendance_events_id_seq TO salomon_intra_app;
