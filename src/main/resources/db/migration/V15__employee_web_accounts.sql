ALTER TABLE intra.staff_users ADD COLUMN employee_id bigint UNIQUE REFERENCES intra.attendance_employees(id);
GRANT INSERT (username,display_name,role,password_hash,active,employee_id,area,company) ON intra.staff_users TO salomon_intra_app;
GRANT UPDATE (role,active,password_hash,failed_attempts,locked_until) ON intra.staff_users TO salomon_intra_app;
DO $$
DECLARE seq text;
BEGIN
  seq := pg_get_serial_sequence('intra.staff_users','id');
  IF seq IS NOT NULL THEN EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO salomon_intra_app',seq); END IF;
END $$;
