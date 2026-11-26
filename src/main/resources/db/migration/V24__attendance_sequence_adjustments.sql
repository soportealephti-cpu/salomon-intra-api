DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.attendance_manual_adjustments ADD COLUMN action varchar(12) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE intra.attendance_manual_adjustments ADD COLUMN source_time time;
ALTER TABLE intra.attendance_manual_adjustments ALTER COLUMN corrected_time DROP NOT NULL;
ALTER TABLE intra.attendance_manual_adjustments ADD CONSTRAINT attendance_adjustment_action_check CHECK(action IN ('LEGACY','SET','FILL','CLEAR') AND ((action='CLEAR')=(corrected_time IS NULL)));
ALTER TABLE intra.attendance_manual_adjustments ALTER COLUMN action SET DEFAULT 'SET';
