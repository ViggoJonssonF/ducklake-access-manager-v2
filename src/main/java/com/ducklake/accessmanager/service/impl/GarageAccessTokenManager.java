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
 * Implements {@link ObjectStoreAccessTokenManager} against the Garage Admin API v2.
 *
 * Used in production on cbhcloud. Communicates with the Admin API via the nginx proxy
 * in ducklake-garage (port 3900), which forwards /v2/* to the internal port 3903.
 * Authenticates with a Bearer token (GARAGE_ADMIN_TOKEN).
 *
 * Key creation flow:
 *   1. POST /v2/CreateKey       → creates the key, returns accessKeyId + secretAccessKey
 *   2. GET  /v2/GetBucketInfo   → resolves the bucket ID from its name (globalAlias)
 *   3. POST /v2/AllowBucketKey  → grants the key access to the bucket with the given permissions
 *
 * API reference: https://garagehq.deuxfleurs.fr/api/garage-admin-v2.html
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

    /**
     * Creates a read-only key for a specific bucket.
     * Steps: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: false)
     */
    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, false);
    }

    /**
     * Creates a read/write key for a specific bucket.
     * Steps: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: true)
     */
    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, true);
    }

    /**
     * Permanently deletes a key.
     * Uses POST /v2/DeleteKey?id={keyId} — the Garage Admin API v2 is RPC-style (POST for all
     * operations), but DeleteKey takes the key ID as a query parameter, not in the request body.
     */
    @Override
    public void deleteKey(String keyId) {
        restTemplate.postForObject(
            adminApiUrl + "/v2/DeleteKey?id=" + keyId,
            new HttpEntity<>(authHeaders()),
            Void.class
        );
    }

    /**
     * Lists all keys registered in Garage.
     * Uses GET /v2/ListKeys — returns id and name per key (secretAccessKey is not available after creation).
     */
    @Override
    public List<AccessKey> listKeys() {
        ResponseEntity<GarageKeyListItem[]> response = restTemplate.exchange(
            adminApiUrl + "/v2/ListKeys",
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
     * Lists all Garage buckets that have a global alias, sorted by name.
     * Uses GET /v2/ListBuckets. Re-uses the GarageBucketResponse record (id + globalAliases).
     */
    @Override
    public List<Bucket> listBuckets() {
        ResponseEntity<GarageBucketResponse[]> response = restTemplate.exchange(
            adminApiUrl + "/v2/ListBuckets",
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
     * Deletes a bucket from Garage by its global alias.
     * Resolves the bucket ID via GetBucketInfo, then calls POST /v2/DeleteBucket.
     * The bucket must be empty; Garage returns an error otherwise.
     */
    @Override
    public void deleteBucket(String bucketName) {
        String bucketId = getBucketId(bucketName);
        restTemplate.postForObject(
            adminApiUrl + "/v2/DeleteBucket?id=" + bucketId,
            new HttpEntity<>(authHeaders()),
            Object.class
        );
        log.info("Garage bucket deleted: {}", bucketName);
    }

    /**
     * Creates a bucket in Garage with the given global alias.
     * Uses POST /v2/CreateBucket. If the bucket already exists Garage returns 400 — we swallow it.
     */
    @Override
    public void createBucket(String bucketName) {
        log.info("Creating Garage bucket: {}", bucketName);
        try {
            restTemplate.postForObject(
                adminApiUrl + "/v2/CreateBucket",
                new HttpEntity<>(Map.of("globalAlias", bucketName), authHeaders()),
                Object.class
            );
            log.info("Garage bucket created: {}", bucketName);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409 || e.getStatusCode().value() == 400) {
                log.info("Garage bucket already exists ({}): {}", e.getStatusCode().value(), bucketName);
            } else {
                log.error("Failed to create Garage bucket {}: {} {}", bucketName, e.getStatusCode(), e.getResponseBodyAsString());
                throw e;
            }
        }
    }

    // --- Private helpers ---

    // Shared logic for creating a key with optional write permission
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

    // Parses a ListKeys item. Key name format: "key-{bucket}|{pgUsername}|{permission}"
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

    // Step 1: POST /v2/CreateKey – creates the key and returns accessKeyId + secretAccessKey
    private GarageKeyResponse postCreateKey(String keyName) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
            Map.of("name", keyName),
            authHeaders()
        );
        GarageKeyResponse created = restTemplate.postForObject(adminApiUrl + "/v2/CreateKey", request, GarageKeyResponse.class);
        if (created == null) throw new IllegalStateException("Garage CreateKey returned null");
        return created;
    }

    // Step 2: GET /v2/GetBucketInfo?globalAlias={bucketName} – resolves the bucket ID
    private String getBucketId(String bucketName) {
        ResponseEntity<GarageBucketResponse> response = restTemplate.exchange(
            adminApiUrl + "/v2/GetBucketInfo?globalAlias=" + bucketName,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageBucketResponse.class
        );
        GarageBucketResponse bucket = response.getBody();
        if (bucket == null) throw new IllegalStateException("Garage GetBucketInfo returned null for bucket: " + bucketName);
        return bucket.id();
    }

    // Step 3: POST /v2/AllowBucketKey – grants the key access to the bucket with the given permissions
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
            adminApiUrl + "/v2/AllowBucketKey",
            new HttpEntity<>(body, authHeaders()),
            Object.class
        );
    }

    // Builds HTTP headers for Admin API calls.
    // Bearer token is only added if GARAGE_ADMIN_TOKEN is configured.
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (adminToken != null && !adminToken.isBlank()) {
            headers.setBearerAuth(adminToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
