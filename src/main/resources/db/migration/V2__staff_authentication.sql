DO $$
BEGIN
  IF current_database() <> 'salomon_intra' THEN
    RAISE EXCEPTION 'Migration target must be salomon_intra';
  END IF;
END $$;

CREATE TABLE intra.staff_users (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username varchar(100) NOT NULL UNIQUE,
  display_name varchar(160) NOT NULL,
  password_hash varchar(100) NOT NULL,
  role varchar(40) NOT NULL DEFAULT 'PERSONAL',
  active boolean NOT NULL DEFAULT true,
  failed_attempts integer NOT NULL DEFAULT 0,
  locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT staff_username_lowercase CHECK (username = lower(username))
);

CREATE TABLE intra.staff_sessions (
  token_hash char(64) PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES intra.staff_users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX staff_sessions_expires ON intra.staff_sessions (expires_at);
CREATE INDEX staff_sessions_user ON intra.staff_sessions (user_id);
