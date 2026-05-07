package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.model.Dataset;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CRUD + lifecycle for datasets. A dataset = (Garage bucket) + (Postgres database)
 * + (metadata row in {@code datasets}). All three are kept in sync here.
 *
 * <h2>Atomic create</h2>
 * <ol>
 *   <li>Create Garage bucket (idempotent — already-exists is treated as OK).</li>
 *   <li>Create Postgres database {@code dl_<bucket_with_underscores>}.</li>
 *   <li>Insert metadata row.</li>
 * </ol>
 * On any failure we attempt to undo earlier steps. Since Garage and Postgres
 * are separate systems, true ACID is impossible — we settle for best-effort
 * cleanup with logging so an admin can sort out a stuck state.
 *
 * <h2>Auto-sync at startup</h2>
 * When the app starts, every Garage bucket without a {@code datasets} row gets
 * one auto-created with sensible defaults (visibility=public, owner=system).
 * This means buckets created before Phase 3 (e.g. {@code demo-dataset} from
 * {@code garage-bootstrap}) "just work" as datasets without a manual migration.
 *
 * <h2>Future: DuckLake catalog initialization</h2>
 * Creating the Postgres DB only gives us an empty database. The
 * {@code ducklake_*} catalog tables get created the first time a writer
 * connects with {@code ATTACH 'ducklake:postgres:dbname=…'}. We do not
 * pre-initialize them here — that would require running DuckDB inside the
 * access-manager. The expected admin flow is therefore:
 *   create dataset → generate readwrite key → run ATTACH once → grant readers.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final JdbcTemplate jdbc;
    private final ObjectStoreAccessTokenManager objectStore;
    private final PostgresAdminOps pgAdmin;

    public DatasetService(
        JdbcTemplate jdbc,
        ObjectStoreAccessTokenManager objectStore,
        PostgresAdminOps pgAdmin
    ) {
        this.jdbc = jdbc;
        this.objectStore = objectStore;
        this.pgAdmin = pgAdmin;
        createTable();
    }

    private void createTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS datasets (
                bucket_name   VARCHAR PRIMARY KEY,
                pg_database   VARCHAR NOT NULL UNIQUE,
                title         VARCHAR NOT NULL,
                description   TEXT,
                owner_email   VARCHAR NOT NULL,
                visibility    VARCHAR NOT NULL DEFAULT 'private'
                              CHECK (visibility IN ('private','public')),
                created_at    TIMESTAMP DEFAULT NOW()
            )
        """);
    }

    /**
     * Sync Garage → datasets table. Best-effort: any failure is logged but does
     * not block startup. Uses {@link PostConstruct} so it runs after the bean
     * graph is wired but before the app accepts traffic.
     */
    @PostConstruct
    public void syncOrphanBuckets() {
        try {
            List<Bucket> garageBuckets = objectStore.listBuckets();
            Set<String> known = new HashSet<>(jdbc.queryForList(
                "SELECT bucket_name FROM datasets", String.class
            ));
            int registered = 0;
            for (Bucket b : garageBuckets) {
                if (known.contains(b.name())) continue;
                String pgDb = pgDatabaseFor(b.name());
                try {
                    pgAdmin.createDatabaseIfMissing(pgDb);
                    jdbc.update("""
                        INSERT INTO datasets
                            (bucket_name, pg_database, title, owner_email, visibility)
                        VALUES (?, ?, ?, 'system', 'public')
                        ON CONFLICT (bucket_name) DO NOTHING
                    """, b.name(), pgDb, b.name());
                    registered++;
                } catch (Exception inner) {
                    log.warn("Failed to auto-register orphan bucket {}: {}", b.name(), inner.getMessage());
                }
            }
            if (registered > 0) {
                log.info("Auto-registered {} orphan bucket(s) as datasets", registered);
            }
        } catch (Exception e) {
            log.warn("Could not sync orphan buckets at startup (Garage unreachable?): {}", e.getMessage());
        }
    }

    // ── Read APIs ───────────────────────────────────────────────────────────

    public List<Dataset> listAll() {
        return jdbc.query(
            "SELECT * FROM datasets ORDER BY created_at DESC",
            this::mapRow
        );
    }

    /**
     * Datasets visible to a non-admin caller: all public datasets plus any
     * private dataset where the email has access via user/group/everyone grant.
     */
    public List<Dataset> listVisibleTo(Set<String> grantedBucketNames) {
        return jdbc.query(
            "SELECT * FROM datasets ORDER BY created_at DESC",
            this::mapRow
        ).stream()
            .filter(d -> Dataset.VISIBILITY_PUBLIC.equals(d.visibility())
                      || grantedBucketNames.contains(d.bucketName()))
            .toList();
    }

    public Dataset findByBucket(String bucketName) {
        List<Dataset> rows = jdbc.query(
            "SELECT * FROM datasets WHERE bucket_name = ?",
            this::mapRow, bucketName
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Atomic-ish create. Bucket creation is idempotent in Garage (createBucket
     * swallows 409). Database creation is checked-then-create. If the metadata
     * insert fails (e.g. bucket already in use by another dataset record), we
     * roll back the just-created Postgres DB but leave the Garage bucket — it
     * may have been pre-existing.
     */
    public Dataset create(String bucketName, String title, String description, String visibility, String ownerEmail) {
        validateBucketName(bucketName);
        validateVisibility(visibility);

        if (findByBucket(bucketName) != null) {
            throw new IllegalStateException("Dataset already exists: " + bucketName);
        }
        String pgDb = pgDatabaseFor(bucketName);

        objectStore.createBucket(bucketName);
        boolean dbCreated = false;
        try {
            pgAdmin.createDatabaseIfMissing(pgDb);
            dbCreated = true;
            jdbc.update("""
                INSERT INTO datasets (bucket_name, pg_database, title, description, owner_email, visibility)
                VALUES (?, ?, ?, ?, ?, ?)
            """, bucketName, pgDb, title, description, ownerEmail, visibility);
        } catch (RuntimeException e) {
            log.error("Dataset create failed for {} — attempting rollback", bucketName, e);
            if (dbCreated) {
                try { pgAdmin.dropDatabaseIfExists(pgDb); } catch (Exception ignore) {}
            }
            throw e;
        }
        return findByBucket(bucketName);
    }

    /**
     * Update title / description / visibility. Pass null for fields you don't want to touch.
     */
    public Dataset update(String bucketName, String title, String description, String visibility) {
        if (visibility != null) validateVisibility(visibility);
        Dataset existing = findByBucket(bucketName);
        if (existing == null) return null;

        jdbc.update("""
            UPDATE datasets
            SET title       = COALESCE(?, title),
                description = COALESCE(?, description),
                visibility  = COALESCE(?, visibility)
            WHERE bucket_name = ?
        """, title, description, visibility, bucketName);
        return findByBucket(bucketName);
    }

    /**
     * Delete dataset + its Garage bucket + its Postgres database + any grants.
     * Bucket must be empty (Garage refuses to delete non-empty buckets).
     * If anything errors mid-flight we propagate so the caller can surface a
     * 409 / 500 to the user. Order is: delete bucket (most likely to fail) →
     * drop DB → delete metadata + grants.
     */
    public void delete(String bucketName) {
        Dataset existing = findByBucket(bucketName);
        if (existing == null) return;
        try {
            objectStore.deleteBucket(bucketName);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                throw new IllegalStateException("Bucket is not empty — empty it first via the S3 API");
            }
            throw e;
        }
        pgAdmin.dropDatabaseIfExists(existing.pgDatabase());
        jdbc.update("DELETE FROM dataset_grants WHERE bucket_name = ?", bucketName);
        jdbc.update("DELETE FROM datasets WHERE bucket_name = ?", bucketName);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Sanitize a bucket name into a valid Postgres database identifier.
     * Garage allows lowercase alphanumeric + hyphen; Postgres identifiers can't
     * have hyphens, so we replace them with underscores and prefix with "dl_"
     * to scope them to our app.
     */
    public static String pgDatabaseFor(String bucketName) {
        return "dl_" + bucketName.replace('-', '_');
    }

    private static void validateBucketName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9\\-]{1,58}[a-z0-9]")) {
            throw new IllegalArgumentException(
                "Invalid bucket name. Must be 3-60 lowercase chars/digits/hyphens, " +
                "starting and ending with a letter or digit. Got: " + name
            );
        }
    }

    private static void validateVisibility(String v) {
        if (!Dataset.VISIBILITY_PRIVATE.equals(v) && !Dataset.VISIBILITY_PUBLIC.equals(v)) {
            throw new IllegalArgumentException("visibility must be 'private' or 'public', got: " + v);
        }
    }

    private Dataset mapRow(ResultSet rs, int i) throws SQLException {
        return new Dataset(
            rs.getString("bucket_name"),
            rs.getString("pg_database"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("owner_email"),
            rs.getString("visibility"),
            rs.getTimestamp("created_at").toLocalDateTime().toString()
        );
    }
}
