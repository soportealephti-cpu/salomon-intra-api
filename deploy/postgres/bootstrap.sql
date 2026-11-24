-- Run as salomon_admin connected ONLY to salomon_intra, with ON_ERROR_STOP=1.
-- Creates no password and does not enable LOGIN. Review before applying.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'salomon_intra' THEN
        RAISE EXCEPTION 'Bootstrap target must be salomon_intra';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'salomon_intra_app') THEN
        CREATE ROLE salomon_intra_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'salomon_intra_migrator') THEN
        CREATE ROLE salomon_intra_migrator NOLOGIN;
    END IF;
END $$;

REVOKE ALL ON DATABASE salomon_intra FROM PUBLIC;
GRANT CONNECT ON DATABASE salomon_intra TO salomon_intra_app, salomon_intra_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS intra AUTHORIZATION salomon_intra_migrator;
CREATE SCHEMA IF NOT EXISTS flyway AUTHORIZATION salomon_intra_migrator;
REVOKE ALL ON SCHEMA intra, flyway FROM PUBLIC;
GRANT USAGE ON SCHEMA intra TO salomon_intra_app;

-- Only future application objects; Flyway's history stays in a private schema.
ALTER DEFAULT PRIVILEGES FOR ROLE salomon_intra_migrator IN SCHEMA intra
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO salomon_intra_app;
ALTER DEFAULT PRIVILEGES FOR ROLE salomon_intra_migrator IN SCHEMA intra
    GRANT USAGE, SELECT ON SEQUENCES TO salomon_intra_app;
COMMIT;
