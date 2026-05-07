package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.model.BucketGrant;
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

    // ── Grants ───────────────────────────────────────────────────────────

    // The /grants endpoints continue to operate on user-type grants only so
    // the existing admin frontend keeps working. Group and @everyone grant
    // CRUD will land alongside the new admin UI in a later phase.

    @GetMapping("/grants")
    public ResponseEntity<List<BucketGrant>> listGrants(
        @RequestParam(required = false) String email,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        List<BucketGrant> result = (email != null && !email.isBlank())
            ? accessService.listUserGrantsForStudent(email)
            : accessService.listUserGrantsAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/grants")
    public ResponseEntity<Void> addGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String studentEmail = body.get("studentEmail");
        String bucketName   = body.get("bucketName");
        if (studentEmail == null || studentEmail.isBlank() || bucketName == null || bucketName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        accessService.grantUser(studentEmail, bucketName);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/grants")
    public ResponseEntity<Void> revokeGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        accessService.revokeUser(body.get("studentEmail"), body.get("bucketName"));
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Jwt jwt) {
        if (!SecurityConfig.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
