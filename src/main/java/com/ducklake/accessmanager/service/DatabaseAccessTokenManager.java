package com.ducklake.accessmanager.service;

import com.ducklake.accessmanager.model.DbCredentials;
import java.util.List;

/**
 * Manages creation and deletion of PostgreSQL users with per-database scope.
 *
 * Each generated user is granted access to exactly one database — the per-dataset
 * Postgres DB created by {@link com.ducklake.accessmanager.service.impl.DatasetService}.
 * This means a user with access to dataset A literally cannot SELECT against
 * dataset B's catalog tables, since they have no CONNECT privilege there.
 *
 * Implemented by
 * {@link com.ducklake.accessmanager.service.impl.PostgresAccessTokenManager}.
 */
public interface DatabaseAccessTokenManager {

    /**
     * Creates a PostgreSQL user with read-only access to the given database.
     * Steps: CREATE USER → GRANT CONNECT on the target db → (in-target-db)
     * GRANT USAGE on schema public → GRANT SELECT on all current and future tables.
     */
    DbCredentials createReadOnlyUser(String database);

    /**
     * Creates a PostgreSQL user with read/write access to the given database.
     * Adds CREATE on schema public so DuckLake can bootstrap its catalog tables
     * on the first {@code ATTACH 'ducklake:postgres:dbname=…'} call.
     */
    DbCredentials createReadWriteUser(String database);

    /**
     * Revokes the user's privileges in {@code database} and drops them
     * cluster-wide. Idempotent on missing users.
     */
    void deleteUser(String username, String database);

    /** Lists all dynamically created users (those with the "dl_" prefix). */
    List<String> listUsers();
}
