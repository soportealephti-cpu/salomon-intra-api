-- Allow only the authenticated SA workflow to remove related local records.
DO $$
BEGIN
 IF current_database() <> 'salomon_intra' THEN RAISE EXCEPTION 'Migration target must be salomon_intra'; END IF;
END $$;
GRANT DELETE ON intra.biometric_attendance_events, intra.attendance_manual_adjustments,
 intra.attendance_observations, intra.payroll_schedules, intra.payroll_incidents,
 intra.biometric_user_provision_commands, intra.device_removal_commands,
 intra.attendance_daily, intra.staff_users, intra.staff_sessions TO salomon_intra_app;
GRANT SELECT ON intra.staff_sessions TO salomon_intra_app;
