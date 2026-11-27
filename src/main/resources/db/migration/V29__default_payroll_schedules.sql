DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.payroll_schedules ADD COLUMN alert_from time CHECK(alert_from>=starts_at AND alert_from<ends_at);
CREATE TABLE intra.payroll_default_schedules(
 effective_from date NOT NULL, weekday smallint NOT NULL CHECK(weekday BETWEEN 1 AND 7),
 starts_at time NOT NULL, ends_at time NOT NULL CHECK(ends_at>starts_at),
 working boolean NOT NULL, alert_from time NOT NULL CHECK(alert_from>=starts_at AND alert_from<ends_at),
 actor_id bigint REFERENCES intra.staff_users(id) ON DELETE SET NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(effective_from,weekday)
);
INSERT INTO intra.payroll_default_schedules(effective_from,weekday,starts_at,ends_at,working,alert_from)
SELECT DATE '2026-11-27', d, CASE WHEN d=6 THEN TIME '08:00' ELSE TIME '08:30' END,
 CASE WHEN d=6 THEN TIME '13:00' ELSE TIME '18:00' END,d<=6,
 CASE WHEN d=6 THEN TIME '08:15' ELSE TIME '08:45' END
FROM generate_series(1,7) d;
GRANT SELECT,INSERT,UPDATE ON intra.payroll_default_schedules TO salomon_intra_app;
