DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.attendance_manual_adjustments DROP CONSTRAINT attendance_adjustment_action_check;
ALTER TABLE intra.attendance_manual_adjustments ADD CONSTRAINT attendance_adjustment_action_check CHECK(
 action IN ('LEGACY','SET','FILL','CLEAR','MOVE_OUT','MOVE_IN')
 AND ((action IN ('CLEAR','MOVE_OUT'))=(corrected_time IS NULL))
);
