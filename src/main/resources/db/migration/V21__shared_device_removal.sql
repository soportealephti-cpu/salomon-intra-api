CREATE TABLE intra.device_removal_commands (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
 pin varchar(64) NOT NULL,
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 status varchar(16) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','sent','confirmed','failed','expired')),
 result_code integer,
 created_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz,
 completed_at timestamptz
);
CREATE UNIQUE INDEX device_removal_open ON intra.device_removal_commands(device_id,pin) WHERE status IN ('pending','sent');
GRANT SELECT,INSERT,UPDATE ON intra.device_removal_commands TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.device_removal_commands_id_seq TO salomon_intra_app;
