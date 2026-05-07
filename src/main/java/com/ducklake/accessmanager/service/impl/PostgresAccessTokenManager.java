package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.model.DbCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implements {@link DatabaseAccessTokenManager} against PostgreSQL via JDBC.
 *
 * Splits work across two connections:
 *
 *   • {@code jdbcTemplate}  — the autowired connection to the access-manager's
 *     home database ({@code ducklake}). Used for cluster-level operations
 *     (CREATE USER, DROP USER, GRANT CONNECT ON DATABASE) which are not bound
 *     to any one database.
 *   • {@code pgAdmin.jdbcFor(database)} — a fresh connection to the target
 *     dataset's own database. Used for per-database GRANT statements
 *     (USAGE / CREATE on schema public, SELECT/INSERT/… on tables, default
 *     privileges) which can only be issued from inside the target db.
 *
 * Users are named:
 *   - Read-only:  "dl_ro_" + 8 random hex chars  (e.g. dl_ro_a3f2b1c9)
 *   - Read/write: "dl_rw_" + 8 random hex chars  (e.g. dl_rw_7e4d8f2a)
 *
 * DDL statements interpolate identifiers directly because PostgreSQL doesn't
 * support bound parameters for them. Identifiers are either UUID-derived
 * (usernames, passwords) or pre-validated (database names go through
 * {@link PostgresAdminOps}).
 */
@Service
public class PostgresAccessTokenManager implements DatabaseAccessTokenManager {

    private static final Logger log = LoggerFactory.getLogger(PostgresAccessTokenManager.class);

    // Hardcoded — Kubernetes service env vars (DUCKLAKE_CATALOG_PORT=tcp://…)
    // can otherwise override a configured property via Spring's relaxed binding.
    private static final int DB_PORT = 5432;

    private final JdbcTemplate jdbcTemplate;
    private final PostgresAdminOps pgAdmin;
    private final String dbHost;

    public PostgresAccessTokenManager(
        JdbcTemplate jdbcTemplate,
        PostgresAdminOps pgAdmin,
        @Value("${ducklake.postgres.host}") String dbHost
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.pgAdmin = pgAdmin;
        this.dbHost = dbHost;
    }

    @Override
    public DbCredentials createReadOnlyUser(String database) {
        String username = "dl_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        // Cluster-level
        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + database + " TO " + username);

        // In the target DB
        JdbcTemplate dbJdbc = pgAdmin.jdbcFor(database);
        dbJdbc.execute("GRANT USAGE ON SCHEMA public TO " + username);
        dbJdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + username);
        // Future tables — DuckLake creates catalog tables lazily on first ATTACH;
        // without this the read-only user can't see them.
        dbJdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + username);

        return new DbCredentials(username, password, dbHost, DB_PORT, database, "read");
    }

    @Override
    public DbCredentials createReadWriteUser(String database) {
        String username = "dl_rw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + database + " TO " + username);

        JdbcTemplate dbJdbc = pgAdmin.jdbcFor(database);
        // CREATE on schema public lets DuckLake bootstrap its catalog tables on
        // the first ATTACH. Without it the writer's first attach fails.
        dbJdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + username);
        dbJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + username);
        dbJdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + username);

        return new DbCredentials(username, password, dbHost, DB_PORT, database, "readwrite");
    }

    @Override
    public void deleteUser(String username, String database) {
        validateUsername(username);

        // In-database revokes first. Wrap in try so a missing target DB
        // (already-dropped dataset) doesn't block the cluster-level cleanup.
        try {
            JdbcTemplate dbJdbc = pgAdmin.jdbcFor(database);
            dbJdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM " + username);
            dbJdbc.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM " + username);
            dbJdbc.execute("REVOKE ALL ON SCHEMA public FROM " + username);
        } catch (Exception e) {
            log.warn("In-database revoke for {} on {} failed (continuing): {}", username, database, e.getMessage());
        }

        try {
            jdbcTemplate.execute("REVOKE CONNECT ON DATABASE " + database + " FROM " + username);
        } catch (Exception e) {
            log.warn("REVOKE CONNECT on {} for {} failed (continuing): {}", database, username, e.getMessage());
        }
        jdbcTemplate.execute("DROP USER IF EXISTS " + username);
    }

    @Override
    public List<String> listUsers() {
        return jdbcTemplate.queryForList(
            "SELECT usename FROM pg_user WHERE usename LIKE 'dl_%'",
            String.class
        );
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches("dl_(ro|rw)_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Invalid username format: " + username);
        }
    }
}
