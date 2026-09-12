package com.kalo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Cross-origin access for a split deployment.
 *
 * The default topology is same-origin: a reverse proxy serves the built
 * frontend and forwards /api to this application, so the browser never makes a
 * cross-origin request and no CORS headers are needed. In that setup
 * {@code app.cors.allowed-origins} stays empty and this contributes nothing.
 *
 * When the frontend is hosted separately, origins are listed exactly. A
 * wildcard is rejected rather than accepted, because with credentials in play
 * "*" would let any site on the internet drive the API as a signed-in user.
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (origins.isEmpty()) {
            log.info("CORS disabled: no allowed origins configured (same-origin deployment).");
            return source;
        }

        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalStateException(
                        "Refusing to start: CORS origin '" + origin + "' contains a wildcard. "
                                + "List exact origins, for example https://app.kalo.al."
                );
            }
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));

        /*
         * The browser sends the token in an Authorization header, not a cookie,
         * so credentials are not required. Leaving this false keeps the rules
         * strict and avoids the cookie/CSRF questions entirely.
         */
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        source.registerCorsConfiguration("/api/**", configuration);

        log.info("CORS enabled for origins: {}", origins);

        return source;
    }
}
