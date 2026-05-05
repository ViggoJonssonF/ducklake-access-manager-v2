package com.ducklake.accessmanager.service;

import java.util.List;
import java.util.Map;

/**
 * Tracks which Keycloak user owns which Garage key.
 *
 * Stored in PostgreSQL table key_user_mapping (created on first use).
 * Used to enforce ownership checks on GET and DELETE, to filter
 * the key list so regular users only see their own keys, and to
 * annotate keys with the creator's email in the list response.
 */
public interface KeyMappingService {
    void saveMapping(String garageKeyId, String keycloakSub, String displayName);
    String findOwner(String garageKeyId);
    List<String> findKeyIdsForUser(String keycloakUser);
    /** Returns a map of garageKeyId → display_name for the given key IDs. */
    Map<String, String> findDisplayNames(List<String> keyIds);
    /** Returns a map of garageKeyId → created_at (ISO string) for the given key IDs. */
    Map<String, String> findCreatedAts(List<String> keyIds);
    void deleteMapping(String garageKeyId);
}
