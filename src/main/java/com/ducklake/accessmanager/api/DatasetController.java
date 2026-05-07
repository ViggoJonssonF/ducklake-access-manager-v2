package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Dataset;
import com.ducklake.accessmanager.service.impl.AccessService;
import com.ducklake.accessmanager.service.impl.DatasetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the dataset concept.
 *
 *   GET    /api/datasets              — list visible to caller.
 *                                       admin sees all; others see public + granted.
 *   GET    /api/datasets/{bucket}     — single dataset (must be visible to caller).
 *   POST   /api/datasets              — create (admin only).
 *   PATCH  /api/datasets/{bucket}     — update title/description/visibility
 *                                       (admin or owner).
 *   DELETE /api/datasets/{bucket}     — delete (admin or owner). Bucket must be empty.
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetService datasets;
    private final AccessService access;

    public DatasetController(DatasetService datasets, AccessService access) {
        this.datasets = datasets;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<Dataset>> list(@AuthenticationPrincipal Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) {
            return ResponseEntity.ok(datasets.listAll());
        }
        String email = emailOf(jwt);
        return ResponseEntity.ok(datasets.listVisibleTo(access.visibleBucketNames(email)));
    }

    @GetMapping("/{bucket}")
    public ResponseEntity<Dataset> get(@PathVariable String bucket, @AuthenticationPrincipal Jwt jwt) {
        Dataset d = datasets.findByBucket(bucket);
        if (d == null) return ResponseEntity.notFound().build();
        if (!canSee(d, jwt)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(d);
    }

    @PostMapping
    public ResponseEntity<Dataset> create(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String bucketName  = body.get("bucketName");
        String title       = body.getOrDefault("title", bucketName);
        String description = body.get("description");
        String visibility  = body.getOrDefault("visibility", Dataset.VISIBILITY_PRIVATE);
        try {
            Dataset created = datasets.create(bucketName, title, description, visibility, emailOf(jwt));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PatchMapping("/{bucket}")
    public ResponseEntity<Dataset> update(
        @PathVariable String bucket,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        Dataset existing = datasets.findByBucket(bucket);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!canMutate(existing, jwt)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            Dataset updated = datasets.update(bucket, body.get("title"), body.get("description"), body.get("visibility"));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{bucket}")
    public ResponseEntity<Void> delete(
        @PathVariable String bucket,
        @AuthenticationPrincipal Jwt jwt
    ) {
        Dataset existing = datasets.findByBucket(bucket);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!canMutate(existing, jwt)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            datasets.delete(bucket);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean canSee(Dataset d, Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) return true;
        if (Dataset.VISIBILITY_PUBLIC.equals(d.visibility())) return true;
        return access.hasAccess(emailOf(jwt), d.bucketName());
    }

    /** Mutate = update or delete. Allowed for admins and the dataset's owner. */
    private boolean canMutate(Dataset d, Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) return true;
        return d.ownerEmail() != null && d.ownerEmail().equalsIgnoreCase(emailOf(jwt));
    }

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
