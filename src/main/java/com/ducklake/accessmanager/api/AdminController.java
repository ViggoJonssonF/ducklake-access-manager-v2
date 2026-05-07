package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.model.Grant;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.service.impl.AccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ObjectStoreAccessTokenManager objectStore;
    private final AccessService accessService;

    public AdminController(ObjectStoreAccessTokenManager objectStore, AccessService accessService) {
        this.objectStore = objectStore;
        this.accessService = accessService;
    }

    // ── Buckets (sourced from Garage) ─────────────────────────────────────

    @GetMapping("/buckets")
    public ResponseEntity<List<Bucket>> listBuckets(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ResponseEntity.ok(objectStore.listBuckets());
    }

    @PostMapping("/buckets")
    public ResponseEntity<Bucket> addBucket(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String name = body.get("name");
        if (name == null || !name.matches("[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]")) {
            return ResponseEntity.badRequest().build();
        }
        objectStore.createBucket(name);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Bucket(name));
    }

    @DeleteMapping("/buckets/{name}")
    public ResponseEntity<Void> deleteBucket(
        @PathVariable String name,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        try {
            objectStore.deleteBucket(name);
        } catch (HttpClientErrorException e) {
            log.warn("Failed to delete Garage bucket {}: {}", name, e.getStatusCode());
            if (e.getStatusCode().value() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Bucket is not empty");
            }
            throw e;
        }
        return ResponseEntity.noContent().build();
    }

    // ── Grants (now: user / group / everyone) ─────────────────────────────
    //
    // Returns the generalized Grant shape (principalType + principalId).
    // The admin frontend reads these fields.
    //
    // POST/DELETE accept either:
    //   • new shape: {principalType, principalId, bucketName}
    //                where principalType ∈ {user, group, everyone}.
    //                principalId is omitted/ignored for type=everyone.
    //   • legacy shape: {studentEmail, bucketName}  (treated as user-type)
    // …so any third-party caller still using the v1 body keeps working.

    @GetMapping("/grants")
    public ResponseEntity<List<Grant>> listGrants(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ResponseEntity.ok(accessService.listAllGrants());
    }

    @PostMapping("/grants")
    public ResponseEntity<Void> addGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String bucketName = body.get("bucketName");
        if (bucketName == null || bucketName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Legacy shape — treat studentEmail as user grant.
        if (body.containsKey("studentEmail") && !body.get("studentEmail").isBlank()) {
            accessService.grantUser(body.get("studentEmail"), bucketName);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }

        String type = body.get("principalType");
        String id   = body.get("principalId");

        switch (type == null ? "" : type) {
            case AccessService.TYPE_USER -> {
                if (id == null || id.isBlank()) return ResponseEntity.badRequest().build();
                accessService.grantUser(id, bucketName);
            }
            case AccessService.TYPE_GROUP -> {
                if (id == null || id.isBlank()) return ResponseEntity.badRequest().build();
                accessService.grantGroup(id, bucketName);
            }
            case AccessService.TYPE_EVERYONE -> {
                accessService.grantEveryone(bucketName);
            }
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/grants")
    public ResponseEntity<Void> revokeGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String bucketName = body.get("bucketName");
        if (bucketName == null || bucketName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Legacy shape
        if (body.containsKey("studentEmail") && !body.get("studentEmail").isBlank()) {
            accessService.revokeUser(body.get("studentEmail"), bucketName);
            return ResponseEntity.noContent().build();
        }

        String type = body.get("principalType");
        String id   = body.get("principalId");
        if (type == null) return ResponseEntity.badRequest().build();
        accessService.revoke(type, id, bucketName);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Jwt jwt) {
        if (!SecurityConfig.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
