package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Group;
import com.ducklake.accessmanager.service.impl.GroupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Group CRUD endpoints.
 *
 *   GET    /api/groups                     — list all (admin-only).
 *   GET    /api/groups/{name}              — single group + members (admin-only).
 *   POST   /api/groups                     — create (admin-only).
 *   DELETE /api/groups/{name}              — delete (admin-only).
 *   POST   /api/groups/{name}/members      — add member by email (admin-only).
 *   DELETE /api/groups/{name}/members      — remove member by email (admin-only).
 *
 * All endpoints require admin. Membership is consumed by {@link
 * com.ducklake.accessmanager.service.impl.AccessService#hasAccess} so non-admins
 * benefit from the access expansion implicitly without ever calling these endpoints.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public ResponseEntity<List<Group>> list(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ResponseEntity.ok(groups.listAll());
    }

    @GetMapping("/{name}")
    public ResponseEntity<Group> get(@PathVariable String name, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        Group g = groups.findByName(name);
        if (g == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(g);
    }

    @PostMapping
    public ResponseEntity<Group> create(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        try {
            Group created = groups.create(body.get("name"), body.get("description"), emailOf(jwt));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        groups.delete(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/members")
    public ResponseEntity<Void> addMember(
        @PathVariable String name,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        try {
            groups.addMember(name, body.get("email"));
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{name}/members")
    public ResponseEntity<Void> removeMember(
        @PathVariable String name,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        groups.removeMember(name, body.get("email"));
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String emailOf(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }

    private static void requireAdmin(Jwt jwt) {
        if (!SecurityConfig.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
