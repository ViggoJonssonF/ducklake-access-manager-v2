package com.ducklake.accessmanager.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, unauthenticated configuration the frontend reads at startup.
 *
 * The original v1 frontend hard-coded the production Keycloak URL and client_id
 * inline. To run the same JS against a local Keycloak (and against any future
 * environment) we expose them here and let the page bootstrap fetch them.
 *
 * Defaults preserve the production behaviour if no overrides are set, so this
 * endpoint is safe to deploy unchanged.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final String keycloakBase;
    private final String clientId;

    public ConfigController(
        @Value("${ducklake.frontend.keycloak-base:https://iam.cloud.cbh.kth.se/realms/cloud/protocol/openid-connect}") String keycloakBase,
        @Value("${ducklake.frontend.client-id:ducklake}") String clientId
    ) {
        this.keycloakBase = keycloakBase;
        this.clientId = clientId;
    }

    @GetMapping
    public Map<String, String> get() {
        return Map.of(
            "keycloakBase", keycloakBase,
            "clientId", clientId
        );
    }
}
