package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.infrastructure.garage.GarageBucketResponse;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyListItem;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyResponse;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Implements {@link ObjectStoreAccessTokenManager} against the Garage Admin API v1.
 *
 * The original v0 of this class targeted the v2 admin API (RPC-style POST endpoints
 * like /v2/ListBuckets, /v2/CreateBucket). Those endpoints don't exist in
 * dxflrs/garage:v1.0.1 — Garage answers `400 InvalidRequest: Unknown API endpoint`.
 * The v1 admin API is the documented stable surface across all Garage 0.9+ /
 * 1.0.x releases, so we use that instead. It also matches what the local
 * `garage-bootstrap` script uses, so the whole stack speaks one API version.
 *
 * Reference: https://garagehq.deuxfleurs.fr/api/garage-admin-v1.html
 *
 * Path / method differences vs v2:
 *
 *   v2                             v1
 *   ─────────────────────────────  ─────────────────────────────────────────
 *   GET  /v2/ListBuckets           GET    /v1/bucket?list
 *   POST /v2/CreateBucket          POST   /v1/bucket
 *   POST /v2/DeleteBucket?id=…     DELETE /v1/bucket?id=…
 *   GET  /v2/GetBucketInfo?…       GET    /v1/bucket?globalAlias=…
 *   GET  /v2/ListKeys              GET    /v1/key?list
 *   POST /v2/CreateKey             POST   /v1/key
 *   POST /v2/DeleteKey?id=…        DELETE /v1/key?id=…
 *   POST /v2/AllowBucketKey        POST   /v1/bucket/allow
 *
 * JSON shapes are identical for the fields we read — Garage uses camelCase across
 * both API versions and our DTOs are tagged with @JsonIgnoreProperties(ignoreUnknown=true).
 */
@Service
public class GarageAccessTokenManager implements ObjectStoreAccessTokenManager {

    private static final Logger log = LoggerFactory.getLogger(GarageAccessTokenManager.class);

    private final RestTemplate restTemplate;
    private final String adminApiUrl;
    private final String adminToken;
    private final String garageEndpoint;
    private final String garageRegion;

    public GarageAccessTokenManager(
        @Value("${garage.admin.url}") String adminApiUrl,
        @Value("${garage.admin.token}") String adminToken,
        @Value("${garage.s3.endpoint}") String garageEndpoint,
        @Value("${garage.s3.region}") String garageRegion
    ) {
        this.restTemplate = new RestTemplate();
        this.adminApiUrl = adminApiUrl;
        this.adminToken = adminToken;
        this.garageEndpoint = garageEndpoint;
        this.garageRegion = garageRegion;
    }

    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, false);
    }

    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, true);
    }

    /**
     * DELETE /v1/key?id={keyId}
     */
    @Override
    public void deleteKey(String keyId) {
        restTemplate.exchange(
            adminApiUrl + "/v1/key?id=" + keyId,
            HttpMethod.DELETE,
            new HttpEntity<>(authHeaders()),
            Void.class
        );
    }

    /**
     * GET /v1/key?list — returns an array of {id, name}.
     */
    @Override
    public List<AccessKey> listKeys() {
        ResponseEntity<GarageKeyListItem[]> response = restTemplate.exchange(
            adminApiUrl + "/v1/key?list",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageKeyListItem[].class
        );

        GarageKeyListItem[] body = response.getBody();
        if (body == null) return List.of();
        return Arrays.stream(body)
            .map(this::parseKeyItem)
            .toList();
    }

    /**
     * GET /v1/bucket?list — returns an array of {id, globalAliases[], localAliases[]}.
     * Filter to buckets that have at least one global alias (those are the ones we manage).
     */
    @Override
    public List<Bucket> listBuckets() {
        ResponseEntity<GarageBucketResponse[]> response = restTemplate.exchange(
            adminApiUrl + "/v1/bucket?list",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageBucketResponse[].class
        );
        GarageBucketResponse[] body = response.getBody();
        if (body == null) return List.of();
        return Arrays.stream(body)
            .filter(b -> b.globalAliases() != null && !b.globalAliases().isEmpty())
            .map(b -> new Bucket(b.globalAliases().get(0)))
            .sorted(Comparator.comparing(Bucket::name))
            .toList();
    }

    /**
     * DELETE /v1/bucket?id={bucketId}. The bucket must be empty;
     * Garage returns an error otherwise — propagated up so callers can surface
     * a 409 to the UI.
     */
    @Override
    public void deleteBucket(String bucketName) {
        String bucketId = getBucketId(bucketName);
        restTemplate.exchange(
            adminApiUrl + "/v1/bucket?id=" + bucketId,
            HttpMethod.DELETE,
            new HttpEntity<>(authHeaders()),
            Void.class
        );
        log.info("Garage bucket deleted: {}", bucketName);
    }

    /**
     * POST /v1/bucket with body {"globalAlias": "<name>"}.
     *
     * If the bucket already exists Garage returns 409 — we swallow that as
     * idempotent. The previous v2 implementation also caught 400 here, but that
     * was a workaround for a different error and masked real bugs (an unknown
     * endpoint also returned 400 and was silently treated as "already exists").
     * With v1 we only catch the genuine conflict status.
     */
    @Override
    public void createBucket(String bucketName) {
        log.info("Creating Garage bucket: {}", bucketName);
        try {
            restTemplate.postForObject(
                adminApiUrl + "/v1/bucket",
                new HttpEntity<>(Map.of("globalAlias", bucketName), authHeaders()),
                Object.class
            );
            log.info("Garage bucket created: {}", bucketName);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                log.info("Garage bucket already exists: {}", bucketName);
            } else {
                log.error("Failed to create Garage bucket {}: {} {}", bucketName, e.getStatusCode(), e.getResponseBodyAsString());
                throw e;
            }
        }
    }

    // --- Private helpers ---

    private AccessKey createKey(String bucketName, String keyName, boolean allowWrite) {
        GarageKeyResponse created = postCreateKey(keyName);
        String bucketId = getBucketId(bucketName);
        grantBucketPermission(bucketId, created.accessKeyId(), allowWrite);

        // Key name format: "key-{bucket}|{pgUsername}|{permission}"
        String[] parts = keyName.split("\\|", 3);
        String pgUsername = parts.length > 1 ? parts[1] : null;
        String permission = allowWrite ? "readwrite" : "read";
        return new AccessKey(created.accessKeyId(), created.secretAccessKey(), bucketName, permission, garageEndpoint, garageRegion, pgUsername);
    }

    // Parses a ListKeys item. Key name format: "key-{bucket}|{pgUsername}|{permission}".
    // Handles old format without permission field for backwards compatibility.
    private AccessKey parseKeyItem(GarageKeyListItem item) {
        String raw = item.name() != null ? item.name() : "";
        String pgUsername = null;
        String permission = null;
        String bucketName = raw;

        if (raw.contains("|")) {
            String[] parts = raw.split("\\|", 3);
            bucketName = parts[0];
            pgUsername = parts.length > 1 ? parts[1] : null;
            permission = parts.length > 2 ? parts[2] : null;
        }

        if (bucketName.startsWith("key-")) bucketName = bucketName.substring(4);

        return new AccessKey(item.id(), null, bucketName, permission, garageEndpoint, garageRegion, pgUsername);
    }

    /** POST /v1/key — body {"name": "..."}. Response carries accessKeyId + secretAccessKey. */
    private GarageKeyResponse postCreateKey(String keyName) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
            Map.of("name", keyName),
            authHeaders()
        );
        GarageKeyResponse created = restTemplate.postForObject(adminApiUrl + "/v1/key", request, GarageKeyResponse.class);
        if (created == null) throw new IllegalStateException("Garage CreateKey returned null");
        return created;
    }

    /** GET /v1/bucket?globalAlias={bucketName} — returns the bucket's full record incl. id. */
    private String getBucketId(String bucketName) {
        ResponseEntity<GarageBucketResponse> response = restTemplate.exchange(
            adminApiUrl + "/v1/bucket?globalAlias=" + bucketName,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageBucketResponse.class
        );
        GarageBucketResponse bucket = response.getBody();
        if (bucket == null) throw new IllegalStateException("Garage GetBucketInfo returned null for bucket: " + bucketName);
        return bucket.id();
    }

    /** POST /v1/bucket/allow — grants the key read/write/owner perms on the bucket. */
    private void grantBucketPermission(String bucketId, String accessKeyId, boolean allowWrite) {
        Map<String, Object> body = Map.of(
            "bucketId", bucketId,
            "accessKeyId", accessKeyId,
            "permissions", Map.of(
                "read", true,
                "write", allowWrite,
                "owner", false
            )
        );
        restTemplate.postForObject(
            adminApiUrl + "/v1/bucket/allow",
            new HttpEntity<>(body, authHeaders()),
            Object.class
        );
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (adminToken != null && !adminToken.isBlank()) {
            headers.setBearerAuth(adminToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
