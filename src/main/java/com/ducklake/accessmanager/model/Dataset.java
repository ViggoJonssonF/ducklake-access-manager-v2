package com.ducklake.accessmanager.model;

/**
 * A dataset is the top-level "thing" admins curate and students browse.
 *
 * Backed by:
 *   • a Garage bucket (same name as bucket_name) for parquet files,
 *   • a per-dataset Postgres database (pg_database, derived from bucket_name)
 *     that holds the DuckLake catalog tables for this dataset only,
 *   • a row in the {@code datasets} table for human metadata
 *     (title, description, owner, visibility).
 *
 * Visibility:
 *   • {@code private} — only users with an explicit grant (or admins) can see/use.
 *   • {@code public}  — every authenticated user can see and read it.
 */
public record Dataset(
    String bucketName,
    String pgDatabase,
    String title,
    String description,
    String ownerEmail,
    String visibility,
    String createdAt
) {
    public static final String VISIBILITY_PRIVATE = "private";
    public static final String VISIBILITY_PUBLIC  = "public";
}
