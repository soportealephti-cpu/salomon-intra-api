DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

-- Metadata only: methods manually confirmed for an employee.
-- No biometric templates, photographs, card numbers or passwords are retained.
CREATE TABLE intra.attendance_employee_auth_methods (
  employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id) ON DELETE CASCADE,
  method varchar(20) NOT NULL CHECK (method IN ('FACE', 'FINGERPRINT', 'PASSWORD', 'CARD')),
  PRIMARY KEY (employee_id, method)
);
GRANT SELECT, INSERT, DELETE ON intra.attendance_employee_auth_methods TO salomon_intra_app;
