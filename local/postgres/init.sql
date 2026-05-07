-- Runs on first Postgres container start (mounted as docker-entrypoint-initdb.d).
-- Anything that needs to exist before the access-manager boots goes here.

-- gen_random_uuid() lives in pgcrypto in older Postgres versions; harmless on 17.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- The access-manager creates the rest of its tables (key_user_mapping,
-- dataset_grants, groups, group_members, datasets) on its own at startup
-- via JdbcTemplate.execute("CREATE TABLE IF NOT EXISTS …") — we don't
-- duplicate the schema here.
