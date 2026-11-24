DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

ALTER TABLE intra.staff_users
  ADD COLUMN area varchar(120),
  ADD COLUMN company varchar(160);

CREATE TABLE intra.attendance_daily (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES intra.staff_users(id) ON DELETE CASCADE,
  work_date date NOT NULL,
  entry_at timestamptz,
  lunch_out_at timestamptz,
  lunch_return_at timestamptz,
  exit_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT attendance_daily_user_date_uk UNIQUE (user_id, work_date),
  CONSTRAINT attendance_lunch_order CHECK (lunch_return_at IS NULL OR lunch_out_at IS NOT NULL),
  CONSTRAINT attendance_exit_order CHECK (exit_at IS NULL OR entry_at IS NOT NULL)
);
CREATE INDEX attendance_daily_work_date_idx ON intra.attendance_daily (work_date);

GRANT SELECT ON intra.staff_users, intra.attendance_daily TO salomon_intra_app;
