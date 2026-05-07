package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.Dataset;
import com.ducklake.accessmanager.model.DbCredentials;
import com.ducklake.accessmanager.model.GeneratedCredentials;
import com.ducklake.accessmanager.model.KeyListItem;
import com.ducklake.accessmanager.model.KeyRequest;
import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.service.KeyMappingService;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.service.impl.AccessService;
import com.ducklake.accessmanager.service.impl.DatasetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for key management.
 *
 * All endpoints require a valid JWT (enforced by SecurityConfig).
 *
 *   POST   /api/keys/generate  — any authenticated user with access to the
 *                                target dataset; readwrite restricted to admins
 *   GET    /api/keys           — admins see all keys; users see only their own
 *   DELETE /api/keys/{keyId}   — admins can delete any key; users only their own
 *
 * Generated keys are scoped to a single dataset's Postgres database (looked up
 * from {@link DatasetService}), so a user with access to dataset A literally
 * cannot read dataset B's catalog tables — there's no CONNECT privilege.
 */
@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;
    private final KeyMappingService keyMapping;
    private final AccessService accessService;
    private final DatasetService datasetService;
    private final String postgresPublicHost;
    private final String legacyDatabase;

    public KeyController(
        ObjectStoreAccessTokenManager objectStore,
        DatabaseAccessTokenManager database,
        KeyMappingService keyMapping,
        AccessService accessService,
        DatasetService datasetService,
        @Value("${ducklake.postgres.public-host}") String postgresPublicHost,
        @Value("${ducklake.postgres.dbname}") String legacyDatabase
    ) {
        this.objectStore = objectStore;
        this.database = database;
        this.keyMapping = keyMapping;
        this.accessService = accessService;
        this.datasetService = datasetService;
        this.postgresPublicHost = postgresPublicHost;
        this.legacyDatabase = legacyDatabase;
    }

    /**
     * Generates a key pair (S3 + per-dataset PostgreSQL user) and returns a
     * ready-to-use DuckDB script.
     *
     * Authorization layers, in order:
     *   1. Bucket name format check
     *   2. Dataset must exist (auto-sync should already have registered any
     *      pre-existing Garage bucket as a dataset on startup)
     *   3. readwrite requires admin role
     *   4. Non-admins need either: dataset is public, or hasAccess() returns
     *      true (covers user, group, and @everyone grants)
     */
    @PostMapping("/generate")
    public ResponseEntity<GeneratedCredentials> generate(
        @RequestBody KeyRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        if (request.bucketName() == null || !request.bucketName().matches("[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]")) {
            return ResponseEntity.badRequest().build();
        }

        Dataset dataset = datasetService.findByBucket(request.bucketName());
        if (dataset == null) {
            // Bucket exists in Garage but no dataset row — startup sync should
            // have caught this. Refuse rather than silently using the legacy
            // shared DB; user can refresh and try again, or admin can run the
            // sync explicitly later.
            return ResponseEntity.notFound().build();
        }

        String keycloakSub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String displayName = email != null ? email : jwt.getClaimAsString("preferred_username");
        boolean admin = SecurityConfig.isAdmin(jwt);

        if ("readwrite".equals(request.permission()) && !admin) {
            return ResponseEntity.status(403).build();
        }

        boolean canRead = admin
            || Dataset.VISIBILITY_PUBLIC.equals(dataset.visibility())
            || accessService.hasAccess(displayName, request.bucketName());
        if (!canRead) {
            return ResponseEntity.status(403).build();
        }

        // PG user is created in the dataset's own DB — full isolation between datasets.
        DbCredentials dbCreds = switch (request.permission()) {
            case "readwrite" -> database.createReadWriteUser(dataset.pgDatabase());
            default          -> database.createReadOnlyUser(dataset.pgDatabase());
        };

        String keyName = "key-" + request.bucketName() + "|" + dbCreds.username() + "|" + request.permission();
        AccessKey s3Key = switch (request.permission()) {
            case "readwrite" -> objectStore.createReadWriteKey(request.bucketName(), keyName);
            default          -> objectStore.createReadOnlyKey(request.bucketName(), keyName);
        };

        keyMapping.saveMapping(s3Key.keyId(), keycloakSub, displayName, dataset.pgDatabase());

        String script = buildDuckdbScript(s3Key, dbCreds, request.bucketName(), postgresPublicHost);
        return ResponseEntity.ok(new GeneratedCredentials(s3Key, dbCreds, script));
    }

    /**
     * Returns keys visible to the caller, each annotated with the creating user's email.
     * Admins see all keys. Regular users see only the keys they created.
     */
    @GetMapping
    public ResponseEntity<List<KeyListItem>> list(@AuthenticationPrincipal Jwt jwt) {
        String keycloakSub = jwt.getSubject();
        boolean admin = SecurityConfig.isAdmin(jwt);

        List<AccessKey> allKeys = objectStore.listKeys();
        List<AccessKey> visibleKeys;

        if (admin) {
            visibleKeys = allKeys;
        } else {
            List<String> ownedIds = keyMapping.findKeyIdsForUser(keycloakSub);
            visibleKeys = allKeys.stream()
                .filter(k -> ownedIds.contains(k.keyId()))
                .toList();
        }

        List<String> keyIds = visibleKeys.stream().map(AccessKey::keyId).toList();
        Map<String, String> displayNames = keyMapping.findDisplayNames(keyIds);
        Map<String, String> createdAts   = keyMapping.findCreatedAts(keyIds);

        List<KeyListItem> result = visibleKeys.stream()
            .map(k -> new KeyListItem(
                k.keyId(), k.secretKey(), k.bucketName(), k.permission(),
                k.endpoint(), k.region(), k.pgUsername(),
                displayNames.get(k.keyId()),
                createdAts.get(k.keyId())
            ))
            .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Deletes a key from both Garage and PostgreSQL.
     *
     * Admins can delete any key. Regular users receive 403 if they do not own the key.
     * The PG user is dropped from the per-dataset DB the key was generated in.
     * Legacy keys created before the per-dataset DB migration fall back to the
     * shared {@code ducklake} database so they can still be cleaned up.
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> delete(
        @PathVariable String keyId,
        @RequestParam(required = false) String pgUsername,
        @AuthenticationPrincipal Jwt jwt
    ) {
        String keycloakSub = jwt.getSubject();
        boolean admin = SecurityConfig.isAdmin(jwt);

        if (!admin) {
            String owner = keyMapping.findOwner(keyId);
            if (!keycloakSub.equals(owner)) {
                return ResponseEntity.status(403).build();
            }
        }

        // Resolve target DB BEFORE we delete the mapping row.
        String targetDb = keyMapping.findDatabase(keyId);
        if (targetDb == null) targetDb = legacyDatabase;

        objectStore.deleteKey(keyId);
        keyMapping.deleteMapping(keyId);

        if (pgUsername != null && !pgUsername.isBlank()) {
            database.deleteUser(pgUsername, targetDb);
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Builds a ready-to-run DuckDB script with the generated credentials.
     * The {@code dbname} in ATTACH points at the dataset's own Postgres
     * database, not the shared catalog DB.
     */
    private String buildDuckdbScript(AccessKey s3Key, DbCredentials db, String bucketName, String pgHost) {
        return """
            -- Run this script from a deployment that can reach the catalog Postgres
            -- and the Garage S3 endpoint. The hostname '%s' is the public endpoint
            -- of the Postgres catalog for this dataset.

            INSTALL ducklake;
            INSTALL postgres;

            LOAD ducklake;
            LOAD postgres;

            CREATE OR REPLACE SECRET (
                TYPE postgres,
                HOST '%s',
                PORT %d,
                DATABASE %s,
                USER '%s',
                PASSWORD '%s'
            );

            CREATE OR REPLACE SECRET garage_secret (
                TYPE s3,
                PROVIDER config,
                KEY_ID '%s',
                SECRET '%s',
                REGION '%s',
                ENDPOINT '%s',
                URL_STYLE 'path',
                USE_SSL false
            );

            ATTACH 'ducklake:postgres:dbname=%s' AS my_ducklake (
                DATA_PATH 's3://%s/'
            );

            USE my_ducklake;
            """.formatted(
                pgHost,
                pgHost, db.port(), db.database(), db.username(), db.password(),
                s3Key.keyId(), s3Key.secretKey(), s3Key.region(), s3Key.endpoint(),
                db.database(), bucketName
        );
    }
}
