package com.ducklake.accessmanager.service;

import java.util.List;
import java.util.Map;

/**
 * Tracks which Keycloak user owns which Garage key, plus the per-dataset
 * Postgres database the key's PG user lives in.
 *
 * Stored in PostgreSQL table {@code key_user_mapping} (created on first use).
 * Used to enforce ownership checks on GET and DELETE, to filter the key list
 * so regular users only see their own keys, and to find the right per-dataset
 * database when revoking privileges at delete-time.
 */
public interface KeyMappingService {
    void saveMapping(String garageKeyId, String keycloakSub, String displayName, String pgDatabase);
    String findOwner(String garageKeyId);
    /** Returns the per-dataset Postgres DB the key's PG user belongs to, or null for legacy rows. */
    String findDatabase(String garageKeyId);
    List<String> findKeyIdsForUser(String keycloakUser);
    Map<String, String> findDisplayNames(List<String> keyIds);
    Map<String, String> findCreatedAts(List<String> keyIds);
    void deleteMapping(String garageKeyId);
}
