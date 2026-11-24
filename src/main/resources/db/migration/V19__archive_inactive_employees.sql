-- Archiving removes test workers from the directory without destroying biometric or payroll history.
ALTER TABLE intra.attendance_employees ADD COLUMN archived_at timestamptz;
GRANT UPDATE (archived_at) ON intra.attendance_employees TO salomon_intra_app;
