DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.attendance_outings
 ADD COLUMN voided_at timestamptz,
 ADD COLUMN void_action varchar(12) CHECK(void_action IN ('DELETED','MOVED')),
 ADD COLUMN void_reason varchar(500),
 ADD COLUMN void_actor_id bigint REFERENCES intra.staff_users(id),
 ADD COLUMN void_actor_username varchar(100),
 ADD COLUMN replaces_id bigint UNIQUE REFERENCES intra.attendance_outings(id),
 ADD CONSTRAINT attendance_outings_void_check CHECK(
 (voided_at IS NULL AND void_action IS NULL AND void_reason IS NULL AND void_actor_id IS NULL AND void_actor_username IS NULL)
 OR (voided_at IS NOT NULL AND void_action IS NOT NULL AND void_reason IS NOT NULL AND length(trim(void_reason))>=3 AND void_actor_id IS NOT NULL AND void_actor_username IS NOT NULL)
 );
DROP INDEX intra.attendance_outings_one_open;
CREATE UNIQUE INDEX attendance_outings_one_open ON intra.attendance_outings(employee_id,work_date) WHERE return_time IS NULL AND voided_at IS NULL;
GRANT UPDATE(voided_at,void_action,void_reason,void_actor_id,void_actor_username) ON intra.attendance_outings TO salomon_intra_app;
