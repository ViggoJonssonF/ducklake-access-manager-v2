package com.ducklake.accessmanager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/", "/index.html", "/favicon.ico").permitAll()
                .requestMatchers("/api/admin/**").authenticated()
                .requestMatchers("/api/buckets").authenticated()
                .requestMatchers("/api/datasets/**").authenticated()
                .requestMatchers("/api/groups/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/keys").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/keys/generate").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/keys/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            );
        return http.build();
    }

    // "admin" in resource_access.ducklake.roles — client role on the ducklake Keycloak client
    @SuppressWarnings("unchecked")
    public static boolean isAdmin(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null) return false;
        Map<String, Object> clientAccess = (Map<String, Object>) resourceAccess.get("ducklake");
        if (clientAccess == null) return false;
        List<String> roles = (List<String>) clientAccess.get("roles");
        return roles != null && roles.contains("admin");
    }
}
