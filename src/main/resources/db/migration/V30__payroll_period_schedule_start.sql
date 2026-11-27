-- Keep V29 checksum intact; extend the initial schedule back to the payroll period start.
INSERT INTO intra.payroll_default_schedules(effective_from,weekday,starts_at,ends_at,working,alert_from)
SELECT DATE '2026-11-26', d, CASE WHEN d=6 THEN TIME '08:00' ELSE TIME '08:30' END,
 CASE WHEN d=6 THEN TIME '13:00' ELSE TIME '18:00' END,d<=6,
 CASE WHEN d=6 THEN TIME '08:15' ELSE TIME '08:45' END
FROM generate_series(1,7) d ON CONFLICT(effective_from,weekday) DO NOTHING;
DELETE FROM intra.payroll_default_schedules
WHERE effective_from=DATE '2026-11-27' AND actor_id IS NULL;
