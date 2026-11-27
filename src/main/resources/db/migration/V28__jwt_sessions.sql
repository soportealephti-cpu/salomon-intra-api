DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
ALTER TABLE intra.staff_sessions ADD COLUMN auth_version smallint NOT NULL DEFAULT 1 CHECK(auth_version IN (1,2));
CREATE TABLE intra.staff_jwt_key (
 id smallint PRIMARY KEY CHECK(id=1), private_key bytea NOT NULL, public_key bytea NOT NULL
);
REVOKE ALL ON intra.staff_jwt_key FROM PUBLIC;
GRANT SELECT,INSERT ON intra.staff_jwt_key TO salomon_intra_app;
GRANT INSERT(auth_version) ON intra.staff_sessions TO salomon_intra_app;
