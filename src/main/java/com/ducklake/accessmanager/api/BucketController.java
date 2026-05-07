package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.service.impl.AccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/buckets")
public class BucketController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final AccessService accessService;

    public BucketController(ObjectStoreAccessTokenManager objectStore, AccessService accessService) {
        this.objectStore = objectStore;
        this.accessService = accessService;
    }

    /**
     * Returns buckets visible to the caller:
     * - Admin: all Garage buckets.
     * - Everyone else: union of direct user grants, group-membership grants,
     *   and any @everyone grants. Computed by AccessService.visibleBucketNames.
     */
    @GetMapping
    public ResponseEntity<List<Bucket>> list(@AuthenticationPrincipal Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) {
            return ResponseEntity.ok(objectStore.listBuckets());
        }
        String email = jwt.getClaimAsString("email");
        if (email == null) email = jwt.getClaimAsString("preferred_username");
        Set<String> visible = accessService.visibleBucketNames(email);
        List<Bucket> filtered = objectStore.listBuckets().stream()
            .filter(b -> visible.contains(b.name()))
            .toList();
        return ResponseEntity.ok(filtered);
    }
}
