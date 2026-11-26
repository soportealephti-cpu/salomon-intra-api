-- Standalone staff accounts and explicit, non-destructive conversion history.
DO $$ BEGIN
 IF current_database() <> 'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF;
END $$;
GRANT UPDATE (employee_id,display_name) ON intra.staff_users TO salomon_intra_app;
CREATE TABLE intra.access_account_conversions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 account_id bigint NOT NULL,
 employee_id bigint NOT NULL,
 actor_id bigint NOT NULL,
 username varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
REVOKE UPDATE,DELETE,TRUNCATE ON intra.access_account_conversions FROM salomon_intra_app;
GRANT SELECT,INSERT ON intra.access_account_conversions TO salomon_intra_app;
GRANT USAGE,SELECT ON SEQUENCE intra.access_account_conversions_id_seq TO salomon_intra_app;
