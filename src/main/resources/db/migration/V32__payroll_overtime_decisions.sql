DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
CREATE TABLE intra.payroll_overtime_decisions(
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 work_date date NOT NULL,
 decision varchar(12) NOT NULL CHECK(decision IN ('APPROVED','REJECTED')),
 entry_time time NOT NULL, exit_time time NOT NULL CHECK(exit_time>entry_time),
 scheduled_start time NOT NULL, scheduled_end time NOT NULL CHECK(scheduled_end>scheduled_start),
 observed_seconds integer NOT NULL CHECK(observed_seconds>0),
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>=3),
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 actor_username varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payroll_overtime_daily_idx ON intra.payroll_overtime_decisions(work_date,employee_id,id DESC);
GRANT SELECT,INSERT ON intra.payroll_overtime_decisions TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.payroll_overtime_decisions_id_seq TO salomon_intra_app;
REVOKE UPDATE,DELETE,TRUNCATE ON intra.payroll_overtime_decisions FROM salomon_intra_app;
