DO $$ BEGIN IF current_database()<>'salomon_intra' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;
-- Renew only the expiry of a valid session; never change its token or owner.
GRANT UPDATE (expires_at) ON intra.staff_sessions TO salomon_intra_app;
