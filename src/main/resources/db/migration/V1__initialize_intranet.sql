DO $$
BEGIN
    IF current_database() <> 'salomon_intra' THEN
        RAISE EXCEPTION 'Migration target must be salomon_intra';
    END IF;
END $$;

CREATE TABLE intra.schema_metadata (
    id smallint PRIMARY KEY CHECK (id = 1),
    service varchar(64) NOT NULL,
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO intra.schema_metadata (id, service) VALUES (1, 'salomon-intra-api');
