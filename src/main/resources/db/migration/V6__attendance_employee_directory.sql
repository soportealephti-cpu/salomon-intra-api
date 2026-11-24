DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- Personal registrado para asistencia, separado de las credenciales de inicio de sesión.
CREATE TABLE intra.attendance_employees (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  full_name varchar(160) NOT NULL,
  employee_code varchar(64) NOT NULL UNIQUE,
  area varchar(120),
  company varchar(160),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- El PIN/ID de usuario del reloj se interpreta en el contexto del equipo.
CREATE TABLE intra.attendance_employee_device_ids (
  employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id) ON DELETE CASCADE,
  device_id bigint NOT NULL REFERENCES intra.biometric_devices(id) ON DELETE CASCADE,
  device_user_id varchar(64) NOT NULL,
  PRIMARY KEY (device_id, device_user_id),
  UNIQUE (employee_id, device_id),
  CONSTRAINT employee_device_user_id_not_empty CHECK (length(trim(device_user_id)) > 0)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON intra.attendance_employees, intra.attendance_employee_device_ids TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.attendance_employees_id_seq TO salomon_intra_app;
