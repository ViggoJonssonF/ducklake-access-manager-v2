package com.ducklake.accessmanager.model;

/**
 * A grant in its generalized shape — supersedes the legacy
 * {@link BucketGrant} which only modelled user-type grants.
 *
 * {@code principalType} is one of {@code user}, {@code group}, {@code everyone}.
 * {@code principalId} is interpreted accordingly:
 *   • user     → email
 *   • group    → group name (matches {@code groups.name})
 *   • everyone → always {@code "*"}
 */
public record Grant(
    String principalType,
    String principalId,
    String bucketName,
    String grantedAt
) {}
