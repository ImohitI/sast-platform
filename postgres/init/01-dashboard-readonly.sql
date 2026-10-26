-- Runs once, when the Postgres container creates its data directory.
-- The consumer owns the schema (Flyway, as user "sast"). dashboard-api only reads, with its own user,
-- so a bug in the dashboard can't write or drop anything.
CREATE ROLE dashboard_ro LOGIN PASSWORD 'dashboard_ro';   -- local POC only
GRANT CONNECT ON DATABASE sast TO dashboard_ro;
GRANT USAGE ON SCHEMA public TO dashboard_ro;
-- Tables don't exist yet (Flyway creates them later), so grant on whatever "sast" creates from now on.
ALTER DEFAULT PRIVILEGES FOR ROLE sast IN SCHEMA public GRANT SELECT ON TABLES TO dashboard_ro;
