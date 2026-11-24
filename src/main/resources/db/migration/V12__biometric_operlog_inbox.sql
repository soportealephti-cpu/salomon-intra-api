DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- Preserve unrecognized ADMS OPERLOG bytes durably before acknowledging.
-- Restricted to backend INSERT/UPDATE: raw operation bytes must not be
-- exposed through the user-facing API or logged.
CREATE TABLE intra.biometric_operlog_inbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
    payload bytea NOT NULL,
    payload_sha256 bytea GENERATED ALWAYS AS (sha256(payload)) STORED,
    first_received_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT biometric_operlog_inbox_payload_size CHECK (octet_length(payload) BETWEEN 1 AND 65536),
    CONSTRAINT biometric_operlog_inbox_unique UNIQUE (device_id, payload_sha256)
);
CREATE INDEX biometric_operlog_inbox_received_at
    ON intra.biometric_operlog_inbox (first_received_at DESC);
GRANT INSERT ON intra.biometric_operlog_inbox TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.biometric_operlog_inbox_id_seq TO salomon_intra_app;
