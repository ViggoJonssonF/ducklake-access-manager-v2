package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.DbCredentials;
import com.ducklake.accessmanager.model.GeneratedCredentials;
import com.ducklake.accessmanager.model.KeyListItem;
import com.ducklake.accessmanager.model.KeyRequest;
import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.service.KeyMappingService;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.service.impl.AccessService;
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
 * The JWT identifies the caller and is used to enforce access rules:
 *
 *   POST   /api/keys/generate  – any authenticated user; readwrite restricted to admins
 *   GET    /api/keys           – admins see all keys; users see only their own
 *   DELETE /api/keys/{keyId}   – admins can delete any key; users can only delete their own
 *
 * Admin role is determined by "admin" in the Keycloak resource_access.ducklake.roles JWT claim.
 */
@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;
    private final KeyMappingService keyMapping;
    private final AccessService accessService;
    private final String postgresPublicHost;

    public KeyController(
        ObjectStoreAccessTokenManager objectStore,
        DatabaseAccessTokenManager database,
        KeyMappingService keyMapping,
        AccessService accessService,
        @Value("${ducklake.postgres.public-host}") String postgresPublicHost
    ) {
        this.objectStore = objectStore;
        this.database = database;
        this.keyMapping = keyMapping;
        this.accessService = accessService;
        this.postgresPublicHost = postgresPublicHost;
    }

    /**
     * Generates a key pair (S3 + PostgreSQL) and returns a ready-to-use DuckDB script.
     *
     * readwrite permission requires admin role — returns 403 otherwise.
     * The caller's email (from JWT email claim, fallback to preferred_username) is saved in key_user_mapping for ownership tracking.
     */
    @PostMapping("/generate")
    public ResponseEntity<GeneratedCredentials> generate(
        @RequestBody KeyRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        if (request.bucketName() == null || !request.bucketName().matches("[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]")) {
            return ResponseEntity.badRequest().build();
        }

        String keycloakSub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String displayName = email != null ? email : jwt.getClaimAsString("preferred_username");
        boolean admin = SecurityConfig.isAdmin(jwt);

        if ("readwrite".equals(request.permission()) && !admin) {
            return ResponseEntity.status(403).build();
        }

        // hasAccess covers direct user grants, group memberships, and @everyone
        if (!admin && !accessService.hasAccess(displayName, request.bucketName())) {
            return ResponseEntity.status(403).build();
        }

        // Create PG user first so its username can be embedded in the Garage key name.
        // This allows the server to correlate keys and PG users when listing, without
        // relying on client-side storage.
        DbCredentials dbCreds = switch (request.permission()) {
            case "readwrite" -> database.createReadWriteUser();
            default -> database.createReadOnlyUser();
        };

        String keyName = "key-" + request.bucketName() + "|" + dbCreds.username() + "|" + request.permission();
        AccessKey s3Key = switch (request.permission()) {
            case "readwrite" -> objectStore.createReadWriteKey(request.bucketName(), keyName);
            default -> objectStore.createReadOnlyKey(request.bucketName(), keyName);
        };

        keyMapping.saveMapping(s3Key.keyId(), keycloakSub, displayName);

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
     *
     * @param keyId      the Garage key ID
     * @param pgUsername the PostgreSQL user to delete alongside the key
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

        objectStore.deleteKey(keyId);
        keyMapping.deleteMapping(keyId);

        if (pgUsername != null && !pgUsername.isBlank()) {
            database.deleteUser(pgUsername);
        }

        return ResponseEntity.noContent().build();
    }

    // Builds a ready-to-run DuckDB script with the generated credentials
    private String buildDuckdbScript(AccessKey s3Key, DbCredentials db, String bucketName, String pgHost) {
        return """
            -- Run this script from a deployment on kthcloud.
            -- The hostname '%s' is only reachable within the cbhcloud cluster.

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
