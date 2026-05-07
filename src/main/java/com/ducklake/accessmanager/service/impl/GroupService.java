package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.Group;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CRUD for {@code groups} and {@code group_members}. The tables themselves
 * are created by {@link AccessService} (since access checks need to JOIN
 * against them); this service owns the read/write API on top.
 *
 * Group membership flows into access decisions automatically through
 * {@link AccessService#hasAccess}: a user with email E gets a bucket grant
 * if any group they belong to has a {@code group}-type grant on that bucket.
 */
@Service
public class GroupService {

    private final JdbcTemplate jdbc;

    public GroupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Group> listAll() {
        List<String> names = jdbc.queryForList(
            "SELECT name FROM groups ORDER BY name", String.class
        );
        return names.stream().map(this::loadOne).toList();
    }

    public Group findByName(String name) {
        validateGroupName(name);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM groups WHERE name = ?", Integer.class, name
        );
        if (count == null || count == 0) return null;
        return loadOne(name);
    }

    public Group create(String name, String description, String createdBy) {
        validateGroupName(name);
        int rows = jdbc.update(
            "INSERT INTO groups (name, description, created_by) VALUES (?, ?, ?) " +
            "ON CONFLICT (name) DO NOTHING",
            name, description, createdBy
        );
        if (rows == 0) {
            throw new IllegalStateException("Group already exists: " + name);
        }
        return loadOne(name);
    }

    /**
     * Drops the group. group_members rows cascade away via the FK ON DELETE CASCADE
     * declared in {@link AccessService#createTables}. Existing dataset_grants
     * rows of type {@code group} that reference this name are NOT deleted —
     * they become dangling and stop matching anyone, which is harmless. The UI
     * surfaces them in the grants list and the admin can revoke them
     * individually if they want a tidy state.
     */
    public void delete(String name) {
        validateGroupName(name);
        jdbc.update("DELETE FROM groups WHERE name = ?", name);
    }

    public void addMember(String groupName, String email) {
        validateGroupName(groupName);
        validateEmail(email);
        jdbc.update(
            "INSERT INTO group_members (group_name, member_email) VALUES (?, ?) " +
            "ON CONFLICT DO NOTHING",
            groupName, email
        );
    }

    public void removeMember(String groupName, String email) {
        validateGroupName(groupName);
        jdbc.update(
            "DELETE FROM group_members WHERE group_name = ? AND member_email = ?",
            groupName, email
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Group loadOne(String name) {
        return jdbc.queryForObject(
            "SELECT name, description, created_by, created_at FROM groups WHERE name = ?",
            (rs, i) -> new Group(
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime().toString(),
                jdbc.queryForList(
                    "SELECT member_email FROM group_members WHERE group_name = ? ORDER BY member_email",
                    String.class, name
                )
            ),
            name
        );
    }

    private static void validateGroupName(String name) {
        // Match the same character set as Postgres identifiers / our other slugs:
        // lowercase letters, digits, hyphens. 2-60 chars.
        if (name == null || !name.matches("[a-z0-9][a-z0-9\\-]{0,59}")) {
            throw new IllegalArgumentException(
                "Invalid group name. Must be 1-60 lowercase chars, digits or hyphens, " +
                "starting with a letter or digit. Got: " + name
            );
        }
    }

    private static void validateEmail(String email) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid email: " + email);
        }
    }
}
