-- Cuentas administrativas del dispositivo, independientes del directorio y la planilla.
CREATE TABLE intra.biometric_device_admins (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
 pin varchar(15) NOT NULL CHECK(pin ~ '^[0-9]{1,15}$'),
 display_name varchar(48) NOT NULL,
 password_ciphertext text NOT NULL,
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(device_id,pin)
);
CREATE TABLE intra.biometric_device_admin_commands (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 admin_id bigint NOT NULL REFERENCES intra.biometric_device_admins(id),
 device_id bigint NOT NULL REFERENCES intra.biometric_devices(id),
 pin varchar(15) NOT NULL,
 display_name varchar(48) NOT NULL,
 password_ciphertext text NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'pending'
   CHECK(status IN ('pending','sent','confirmed','failed','expired')),
 result_code integer,
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz,
 completed_at timestamptz
);
CREATE UNIQUE INDEX biometric_admin_one_open ON intra.biometric_device_admin_commands(admin_id) WHERE status IN ('pending','sent');
CREATE INDEX biometric_admin_command_by_device ON intra.biometric_device_admin_commands(device_id,status,created_at);
GRANT SELECT,INSERT,UPDATE ON intra.biometric_device_admins,intra.biometric_device_admin_commands TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.biometric_device_admins_id_seq,intra.biometric_device_admin_commands_id_seq TO salomon_intra_app;
