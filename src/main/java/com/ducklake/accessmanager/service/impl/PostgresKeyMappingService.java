package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.service.KeyMappingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostgresKeyMappingService implements KeyMappingService {

    private final JdbcTemplate jdbc;

    public PostgresKeyMappingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS key_user_mapping (
                garage_key_id VARCHAR PRIMARY KEY,
                keycloak_sub  VARCHAR NOT NULL,
                display_name  VARCHAR,
                created_at    TIMESTAMP DEFAULT NOW()
            )
        """);
        // Added in Phase 3: track which per-dataset Postgres DB the key's PG
        // user lives in. Older rows (created before this migration) keep the
        // column null, and KeyController falls back to the shared `ducklake`
        // DB for those during cleanup.
        jdbc.execute("ALTER TABLE key_user_mapping ADD COLUMN IF NOT EXISTS pg_database VARCHAR");
    }

    @Override
    public void saveMapping(String garageKeyId, String keycloakSub, String displayName, String pgDatabase) {
        jdbc.update(
            "INSERT INTO key_user_mapping (garage_key_id, keycloak_sub, display_name, pg_database) " +
            "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            garageKeyId, keycloakSub, displayName, pgDatabase
        );
    }

    @Override
    public String findOwner(String garageKeyId) {
        List<String> rows = jdbc.queryForList(
            "SELECT keycloak_sub FROM key_user_mapping WHERE garage_key_id = ?",
            String.class, garageKeyId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public String findDatabase(String garageKeyId) {
        List<String> rows = jdbc.queryForList(
            "SELECT pg_database FROM key_user_mapping WHERE garage_key_id = ?",
            String.class, garageKeyId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<String> findKeyIdsForUser(String keycloakSub) {
        return jdbc.queryForList(
            "SELECT garage_key_id FROM key_user_mapping WHERE keycloak_sub = ?",
            String.class, keycloakSub
        );
    }

    @Override
    public Map<String, String> findDisplayNames(List<String> keyIds) {
        if (keyIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(keyIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT garage_key_id, display_name FROM key_user_mapping WHERE garage_key_id IN (" + placeholders + ")",
            keyIds.toArray()
        );
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("garage_key_id"), (String) row.get("display_name"));
        }
        return result;
    }

    @Override
    public Map<String, String> findCreatedAts(List<String> keyIds) {
        if (keyIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(keyIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT garage_key_id, created_at FROM key_user_mapping WHERE garage_key_id IN (" + placeholders + ")",
            keyIds.toArray()
        );
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object ts = row.get("created_at");
            result.put((String) row.get("garage_key_id"), ts != null ? ts.toString() : null);
        }
        return result;
    }

    @Override
    public void deleteMapping(String garageKeyId) {
        jdbc.update("DELETE FROM key_user_mapping WHERE garage_key_id = ?", garageKeyId);
    }
}
