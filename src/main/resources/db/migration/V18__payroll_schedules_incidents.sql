CREATE TABLE intra.payroll_schedules (
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 effective_from date NOT NULL,
 weekday smallint NOT NULL CHECK(weekday BETWEEN 1 AND 7),
 starts_at time NOT NULL,
 ends_at time NOT NULL CHECK(ends_at > starts_at),
 working boolean NOT NULL,
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(employee_id,effective_from,weekday)
);
CREATE TABLE intra.payroll_incidents (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES intra.attendance_employees(id),
 work_date date NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('NONE','FALTA','DM','VACACIONES','FERIADO','LCGH','LSGH','PERMISO')),
 note varchar(500) NOT NULL DEFAULT '',
 actor_id bigint NOT NULL REFERENCES intra.staff_users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payroll_incident_lookup ON intra.payroll_incidents(employee_id,work_date,id DESC);
GRANT SELECT,INSERT,UPDATE ON intra.payroll_schedules TO salomon_intra_app;
GRANT SELECT,INSERT ON intra.payroll_incidents TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.payroll_incidents_id_seq TO salomon_intra_app;
