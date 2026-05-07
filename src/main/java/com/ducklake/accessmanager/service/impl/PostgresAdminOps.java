package com.ducklake.accessmanager.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

/**
 * Cluster-level Postgres admin operations: CREATE / DROP DATABASE, plus
 * a helper that builds a {@link JdbcTemplate} pointed at a *specific* database
 * so callers can run grants inside it.
 *
 * Why split this out from {@link PostgresAccessTokenManager}? CREATE DATABASE
 * cannot run inside a transaction and must be issued from a connection that
 * is *not* connected to the database being created. The default app
 * {@code JdbcTemplate} is already connected to the access-manager's home DB
 * ({@code ducklake}), which suits us — but having one place that owns the
 * cross-database admin work keeps the user-management code tidy.
 */
@Service
public class PostgresAdminOps {

    private static final int DB_PORT = 5432;

    private final JdbcTemplate jdbc;
    private final String dbHost;
    private final String adminUser;
    private final String adminPassword;

    public PostgresAdminOps(
        JdbcTemplate jdbc,
        @Value("${ducklake.postgres.host}") String dbHost,
        @Value("${spring.datasource.username}") String adminUser,
        @Value("${spring.datasource.password}") String adminPassword
    ) {
        this.jdbc = jdbc;
        this.dbHost = dbHost;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    /** True iff a database with this exact name exists in the cluster. */
    public boolean databaseExists(String database) {
        validateIdentifier(database);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_database WHERE datname = ?",
            Integer.class, database
        );
        return count != null && count > 0;
    }

    /** Idempotent: noop if the database already exists. */
    public void createDatabaseIfMissing(String database) {
        validateIdentifier(database);
        if (databaseExists(database)) return;
        // Identifier interpolated directly because PostgreSQL doesn't support
        // bound parameters in DDL. Pre-validated by validateIdentifier above.
        jdbc.execute("CREATE DATABASE " + database);
    }

    /**
     * Idempotent drop. Uses {@code WITH (FORCE)} so any lingering connections
     * (e.g. from clients that didn't disconnect cleanly) get terminated rather
     * than blocking the drop indefinitely.
     */
    public void dropDatabaseIfExists(String database) {
        validateIdentifier(database);
        if (!databaseExists(database)) return;
        jdbc.execute("DROP DATABASE " + database + " WITH (FORCE)");
    }

    /**
     * Returns a {@link JdbcTemplate} connected to {@code database} as the
     * admin user. The caller should use it for in-database GRANT/REVOKE
     * statements that can't be issued from the home DB.
     *
     * The underlying {@link DriverManagerDataSource} is connection-per-call,
     * so it's fine to discard after a few statements — no pool to worry about.
     */
    public JdbcTemplate jdbcFor(String database) {
        validateIdentifier(database);
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(String.format("jdbc:postgresql://%s:%d/%s", dbHost, DB_PORT, database));
        ds.setUsername(adminUser);
        ds.setPassword(adminPassword);
        return new JdbcTemplate(ds);
    }

    /**
     * Postgres identifier safety: lowercase letters/digits/underscore, must
     * start with a letter or underscore, max 63 chars. We never accept
     * caller-supplied database names without going through this — bucket
     * names are already constrained, but defense in depth doesn't hurt.
     */
    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid Postgres identifier: " + name);
        }
    }
}
