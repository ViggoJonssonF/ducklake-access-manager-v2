package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.BucketGrant;
import com.ducklake.accessmanager.model.Grant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generalized access service. Replaces the v1 {@code GrantService} which only
 * understood "this email may use this bucket" grants.
 *
 * A grant is now {@code (bucket_name, principal_type, principal_id)} where
 * {@code principal_type} is one of:
 *
 *   <ul>
 *     <li>{@code user}     — {@code principal_id} is the user's email.
 *                            Equivalent to the old student_grants row.</li>
 *     <li>{@code group}    — {@code principal_id} is a group name from the
 *                            {@code groups} table; everyone in
 *                            {@code group_members} inherits the grant.</li>
 *     <li>{@code everyone} — {@code principal_id} is always {@code "*"}.
 *                            Any authenticated user has access.</li>
 *   </ul>
 *
 * The {@code groups} / {@code group_members} tables are created here so foreign
 * keys can reference them, but actual group CRUD (admin endpoints, UI) lands in
 * a later phase. For now the tables stay empty and only {@code user} /
 * {@code everyone} grants are exercised through {@link
 * com.ducklake.accessmanager.api.AdminController}.
 *
 * Backwards compatibility: rows in the legacy {@code student_grants} table are
 * automatically migrated to {@code dataset_grants} as {@code user}-type
 * principals on first startup. The old table is left in place so we can roll
 * back if anything goes sideways — drop it manually once you trust the new
 * schema.
 */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    public static final String TYPE_USER     = "user";
    public static final String TYPE_GROUP    = "group";
    public static final String TYPE_EVERYONE = "everyone";
    public static final String EVERYONE_ID   = "*";

    private final JdbcTemplate jdbc;

    public AccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createTables();
        migrateLegacyGrants();
    }

    private void createTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS groups (
                name        VARCHAR PRIMARY KEY,
                description VARCHAR,
                created_by  VARCHAR,
                created_at  TIMESTAMP DEFAULT NOW()
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS group_members (
                group_name   VARCHAR NOT NULL REFERENCES groups(name) ON DELETE CASCADE,
                member_email VARCHAR NOT NULL,
                added_at     TIMESTAMP DEFAULT NOW(),
                PRIMARY KEY (group_name, member_email)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS dataset_grants (
                bucket_name    VARCHAR NOT NULL,
                principal_type VARCHAR NOT NULL CHECK (principal_type IN ('user', 'group', 'everyone')),
                principal_id   VARCHAR NOT NULL,
                granted_at     TIMESTAMP DEFAULT NOW(),
                PRIMARY KEY (bucket_name, principal_type, principal_id)
            )
        """);
    }

    private void migrateLegacyGrants() {
        Integer hasLegacy = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'student_grants'",
            Integer.class
        );
        if (hasLegacy == null || hasLegacy == 0) {
            return;
        }
        int migrated = jdbc.update("""
            INSERT INTO dataset_grants (bucket_name, principal_type, principal_id, granted_at)
            SELECT bucket_name, 'user', student_email, granted_at
            FROM student_grants
            ON CONFLICT (bucket_name, principal_type, principal_id) DO NOTHING
        """);
        if (migrated > 0) {
            log.info("Migrated {} row(s) from student_grants → dataset_grants (as type=user)", migrated);
        }
    }

    // ── Read APIs (used during key generation and bucket listing) ────────────

    /**
     * True iff the email has access to the bucket via:
     *   • a direct user grant, or
     *   • an everyone grant, or
     *   • a group grant where the email is a member.
     */
    public boolean hasAccess(String email, String bucketName) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM dataset_grants
            WHERE bucket_name = ?
              AND (
                   (principal_type = 'user'     AND principal_id = ?)
                OR (principal_type = 'everyone')
                OR (principal_type = 'group'    AND principal_id IN (
                      SELECT group_name FROM group_members WHERE member_email = ?
                   ))
              )
        """, Integer.class, bucketName, email, email);
        return count != null && count > 0;
    }

    /**
     * All bucket names the email can see, deduplicated across grant types.
     */
    public Set<String> visibleBucketNames(String email) {
        List<String> rows = jdbc.queryForList("""
            SELECT DISTINCT bucket_name FROM dataset_grants
            WHERE (principal_type = 'user' AND principal_id = ?)
               OR (principal_type = 'everyone')
               OR (principal_type = 'group' AND principal_id IN (
                   SELECT group_name FROM group_members WHERE member_email = ?
               ))
        """, String.class, email, email);
        return new HashSet<>(rows);
    }

    // ── Backwards-compatible CRUD for user-type grants ───────────────────────
    //
    // The existing AdminController endpoints (/api/admin/grants) only deal with
    // user grants. These methods preserve that surface so the legacy frontend
    // keeps working. Group / everyone CRUD is added in a later phase together
    // with the admin UI for them.

    public List<BucketGrant> listUserGrantsAll() {
        return jdbc.query(
            "SELECT principal_id AS student_email, bucket_name, granted_at " +
            "FROM dataset_grants WHERE principal_type = 'user' " +
            "ORDER BY principal_id, bucket_name",
            this::mapBucketGrant
        );
    }

    public List<BucketGrant> listUserGrantsForStudent(String email) {
        return jdbc.query(
            "SELECT principal_id AS student_email, bucket_name, granted_at " +
            "FROM dataset_grants WHERE principal_type = 'user' AND principal_id = ? " +
            "ORDER BY bucket_name",
            this::mapBucketGrant, email
        );
    }

    public void grantUser(String email, String bucketName) {
        jdbc.update(
            "INSERT INTO dataset_grants (bucket_name, principal_type, principal_id) " +
            "VALUES (?, 'user', ?) ON CONFLICT DO NOTHING",
            bucketName, email
        );
    }

    public void revokeUser(String email, String bucketName) {
        jdbc.update(
            "DELETE FROM dataset_grants " +
            "WHERE bucket_name = ? AND principal_type = 'user' AND principal_id = ?",
            bucketName, email
        );
    }

    // ── Generalized CRUD (user / group / everyone) ─────────────────────────

    /**
     * Lists every grant regardless of principal type, newest first within bucket.
     */
    public List<Grant> listAllGrants() {
        return jdbc.query(
            "SELECT principal_type, principal_id, bucket_name, granted_at " +
            "FROM dataset_grants ORDER BY bucket_name, principal_type, principal_id",
            this::mapGrant
        );
    }

    public List<Grant> listGrantsForBucket(String bucketName) {
        return jdbc.query(
            "SELECT principal_type, principal_id, bucket_name, granted_at " +
            "FROM dataset_grants WHERE bucket_name = ? " +
            "ORDER BY principal_type, principal_id",
            this::mapGrant, bucketName
        );
    }

    public void grantGroup(String groupName, String bucketName) {
        jdbc.update(
            "INSERT INTO dataset_grants (bucket_name, principal_type, principal_id) " +
            "VALUES (?, 'group', ?) ON CONFLICT DO NOTHING",
            bucketName, groupName
        );
    }

    public void grantEveryone(String bucketName) {
        jdbc.update(
            "INSERT INTO dataset_grants (bucket_name, principal_type, principal_id) " +
            "VALUES (?, 'everyone', ?) ON CONFLICT DO NOTHING",
            bucketName, EVERYONE_ID
        );
    }

    /**
     * Generic revoke. {@code principalId} is ignored when type is {@code everyone}
     * (there is only one row per bucket for everyone, identified by the {@code "*"} marker).
     */
    public void revoke(String principalType, String principalId, String bucketName) {
        String id = TYPE_EVERYONE.equals(principalType) ? EVERYONE_ID : principalId;
        jdbc.update(
            "DELETE FROM dataset_grants " +
            "WHERE bucket_name = ? AND principal_type = ? AND principal_id = ?",
            bucketName, principalType, id
        );
    }

    private BucketGrant mapBucketGrant(ResultSet rs, int i) throws SQLException {
        return new BucketGrant(
            rs.getString("student_email"),
            rs.getString("bucket_name"),
            rs.getTimestamp("granted_at").toLocalDateTime()
        );
    }

    private Grant mapGrant(ResultSet rs, int i) throws SQLException {
        return new Grant(
            rs.getString("principal_type"),
            rs.getString("principal_id"),
            rs.getString("bucket_name"),
            rs.getTimestamp("granted_at").toLocalDateTime().toString()
        );
    }
}
