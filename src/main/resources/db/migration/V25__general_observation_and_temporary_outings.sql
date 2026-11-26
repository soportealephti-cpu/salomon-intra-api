DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.attendance_observations DROP CONSTRAINT attendance_observations_slot_check;
ALTER TABLE intra.attendance_observations ADD CONSTRAINT attendance_observations_slot_check CHECK(slot IN (1,2,3));
CREATE TABLE intra.attendance_outings (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 work_date date NOT NULL,
 departure time NOT NULL,
 observation varchar(1000) NOT NULL CHECK(length(trim(observation))>0),
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 actor_username varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 return_time time,
 return_actor_id bigint REFERENCES intra.staff_users(id),
 return_actor_username varchar(100),
 returned_at timestamptz,
 return_observation varchar(1000) NOT NULL DEFAULT '',
 CHECK(return_time IS NULL OR return_time>departure),
 CHECK((return_time IS NULL AND return_actor_id IS NULL AND return_actor_username IS NULL AND returned_at IS NULL)
    OR (return_time IS NOT NULL AND return_actor_id IS NOT NULL AND return_actor_username IS NOT NULL AND returned_at IS NOT NULL))
);
CREATE INDEX attendance_outings_daily_idx ON intra.attendance_outings(work_date,employee_id,departure);
CREATE UNIQUE INDEX attendance_outings_one_open ON intra.attendance_outings(employee_id,work_date) WHERE return_time IS NULL;
REVOKE UPDATE,DELETE,TRUNCATE ON intra.attendance_outings FROM salomon_intra_app;
GRANT SELECT,INSERT ON intra.attendance_outings TO salomon_intra_app;
GRANT UPDATE(return_time,return_actor_id,return_actor_username,returned_at,return_observation) ON intra.attendance_outings TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.attendance_outings_id_seq TO salomon_intra_app;
