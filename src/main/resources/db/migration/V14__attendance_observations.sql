CREATE TABLE intra.attendance_observations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 work_date date NOT NULL,
 slot smallint NOT NULL CHECK (slot IN (1,2)),
 observation varchar(1000) NOT NULL DEFAULT '',
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 actor_username varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attendance_observations_daily_idx
 ON intra.attendance_observations(work_date,employee_id,slot,id DESC);
GRANT SELECT, INSERT ON intra.attendance_observations TO salomon_intra_app;
GRANT USAGE, SELECT ON SEQUENCE intra.attendance_observations_id_seq TO salomon_intra_app;
