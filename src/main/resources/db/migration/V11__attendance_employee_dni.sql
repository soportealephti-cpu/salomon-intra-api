DO $$
BEGIN
 IF current_database() <> 'salomon_intra' THEN
  RAISE EXCEPTION 'Migration target must be salomon_intra';
 END IF;
END $$;

-- Existing imported users may not have a verified DNI yet.
-- Store it as text to preserve leading zeros; never infer it from device PIN.
ALTER TABLE intra.attendance_employees
  ADD COLUMN dni varchar(8);
ALTER TABLE intra.attendance_employees
  ADD CONSTRAINT attendance_employee_dni_format
  CHECK (dni IS NULL OR dni ~ '^[0-9]{8}$');
CREATE UNIQUE INDEX attendance_employee_dni_unique
  ON intra.attendance_employees(dni) WHERE dni IS NOT NULL;
