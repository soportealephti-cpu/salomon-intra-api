DO $$
DECLARE c record;
BEGIN
  IF current_database() <> 'salomon_intra' THEN RAISE EXCEPTION 'Migration target must be salomon_intra'; END IF;
  FOR c IN SELECT conname FROM pg_constraint
    WHERE conrelid='intra.staff_users'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) ~* '\mrole\M'
  LOOP EXECUTE format('ALTER TABLE intra.staff_users DROP CONSTRAINT %I', c.conname); END LOOP;
END $$;
-- Preserve existing roles, if any, without granting them new permissions.
CREATE TABLE intra.staff_roles (code varchar(64) PRIMARY KEY);
INSERT INTO intra.staff_roles(code) VALUES ('SA'),('ADMIN'),('GERENCIA'),('RECEPCION');
INSERT INTO intra.staff_roles(code) SELECT DISTINCT role FROM intra.staff_users WHERE role IS NOT NULL ON CONFLICT DO NOTHING;
ALTER TABLE intra.staff_users ADD CONSTRAINT staff_users_role_fk FOREIGN KEY(role) REFERENCES intra.staff_roles(code);
GRANT SELECT ON intra.staff_roles TO salomon_intra_app;

CREATE TABLE intra.attendance_manual_adjustments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 work_date date NOT NULL,
 field varchar(20) NOT NULL CHECK(field IN ('ENTRY','EXIT','LUNCH_OUT','LUNCH_RETURN')),
 previous_time time,
 corrected_time time NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason)) >= 5),
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 actor_username varchar(100) NOT NULL,
 actor_role varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attendance_adjustments_day ON intra.attendance_manual_adjustments(work_date,employee_id,field,id DESC);
GRANT SELECT,INSERT ON intra.attendance_manual_adjustments TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.attendance_manual_adjustments_id_seq TO salomon_intra_app;
