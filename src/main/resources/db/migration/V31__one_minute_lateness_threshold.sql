-- Apply the one-minute late-arrival threshold from the current payroll period.
-- Preserve individual hours, working days and earlier schedule history.
DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
INSERT INTO intra.payroll_schedules(employee_id,effective_from,weekday,starts_at,ends_at,working,actor_id,alert_from)
SELECT employee_id,DATE '2026-11-26',weekday,starts_at,ends_at,working,actor_id,
 CASE WHEN (starts_at + interval '1 minute')::time > starts_at
       AND (starts_at + interval '1 minute')::time < ends_at
      THEN (starts_at + interval '1 minute')::time ELSE starts_at END
FROM (
 SELECT DISTINCT ON(employee_id,weekday) employee_id,weekday,starts_at,ends_at,working,actor_id
 FROM intra.payroll_schedules WHERE effective_from<DATE '2026-11-26'
 ORDER BY employee_id,weekday,effective_from DESC
) previous_schedule
ON CONFLICT(employee_id,effective_from,weekday) DO NOTHING;

UPDATE intra.payroll_schedules
SET alert_from=CASE WHEN (starts_at + interval '1 minute')::time > starts_at
                   AND (starts_at + interval '1 minute')::time < ends_at
               THEN (starts_at + interval '1 minute')::time ELSE starts_at END,
    updated_at=now()
WHERE effective_from>=DATE '2026-11-26';

UPDATE intra.payroll_default_schedules
SET alert_from=CASE WHEN (starts_at + interval '1 minute')::time > starts_at
                   AND (starts_at + interval '1 minute')::time < ends_at
               THEN (starts_at + interval '1 minute')::time ELSE starts_at END,
    updated_at=now()
WHERE effective_from>=DATE '2026-11-26';
