package com.ducklake.accessmanager.model;

/**
 * Response body for GET /api/keys. Extends AccessKey with createdBy — the email
 * of the Keycloak user who generated the key, looked up from key_user_mapping.
 */
public record KeyListItem(
    String keyId,
    String secretKey,
    String bucketName,
    String permission,
    String endpoint,
    String region,
    String pgUsername,
    String createdBy,
    String createdAt
) {}
