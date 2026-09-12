package com.kalo.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Refuses to start when development settings are pointed at something that is
 * not a developer's machine.
 *
 * Removing {@code spring.profiles.default=dev} closes the main path to that
 * mistake, but it relies on every deployment remembering to set a profile. This
 * is the backstop: it fails loudly at startup rather than letting the
 * application run with a publicly known signing key, or seed demo accounts into
 * a real database.
 *
 * Both checks are cheap and run once, so there is no reason not to keep them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevelopmentSafetyGuard {

    /**
     * The value committed in application-dev.properties. Anything signing
     * tokens with this key is trivially forgeable by anyone who has read the
     * repository.
     */
    private static final String DEV_JWT_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWZvci1sb2NhbC1rYWxvLWJhY2tlbmQtZGV2ZWxvcG1lbnQ=";

    private static final List<String> LOCAL_DB_HOSTS =
            List.of("localhost", "127.0.0.1", "::1", "host.docker.internal", "postgres", "db");

    private final Environment environment;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    /*
     * Defaulted because the URL is not always a property: under Testcontainers
     * the datasource is configured programmatically. An unreadable URL is
     * treated as non-local by the checks below, which is the safe direction.
     */
    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${app.dev.seed.enabled:false}")
    private boolean seedEnabled;

    @PostConstruct
    void verifyDevelopmentSettingsAreLocal() {

        boolean devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");

        check(jwtSecret, datasourceUrl, devProfile, seedEnabled);

        if (devProfile) {
            log.warn(
                    "Running with the 'dev' profile: throwaway JWT secret, demo seeding {}.",
                    seedEnabled ? "enabled" : "disabled"
            );
        }
    }

    /**
     * The decision itself, separated from how the values are obtained so the
     * rules can be tested directly. Throws when the combination is unsafe and
     * returns quietly when it is not.
     */
    void check(String jwtSecret, String datasourceUrl, boolean devProfile, boolean seedEnabled) {

        boolean localDatabase = pointsAtLocalDatabase(datasourceUrl);

        if (DEV_JWT_SECRET.equals(jwtSecret) && !devProfile) {
            throw new IllegalStateException(
                    "Refusing to start: the development JWT secret is in use without the 'dev' "
                            + "profile. That key is committed to source control, so tokens signed "
                            + "with it can be forged by anyone. Set JWT_SECRET to a private value."
            );
        }

        if (DEV_JWT_SECRET.equals(jwtSecret) && !localDatabase) {
            throw new IllegalStateException(
                    "Refusing to start: the development JWT secret is in use against a non-local "
                            + "database (" + hostOf(datasourceUrl) + "). Set JWT_SECRET to a "
                            + "private value for this environment."
            );
        }

        if (seedEnabled && !localDatabase) {
            throw new IllegalStateException(
                    "Refusing to start: demo data seeding is enabled against a non-local database ("
                            + hostOf(datasourceUrl) + "). The seeder creates accounts whose "
                            + "passwords are published in the README. Set app.dev.seed.enabled=false."
            );
        }
    }

    /**
     * Host-only check on the JDBC URL. Deliberately conservative: anything this
     * cannot confidently read as local is treated as remote, so an unusual URL
     * fails closed rather than open.
     */
    private boolean pointsAtLocalDatabase(String url) {

        String host = hostOf(url);

        return host != null && LOCAL_DB_HOSTS.contains(host);
    }

    private String hostOf(String jdbcUrl) {

        if (jdbcUrl == null) {
            return null;
        }

        // jdbc:postgresql://host:5432/db?params
        int schemeEnd = jdbcUrl.indexOf("//");
        if (schemeEnd < 0) {
            return null;
        }

        String remainder = jdbcUrl.substring(schemeEnd + 2);
        int hostEnd = indexOfFirst(remainder, ':', '/', '?');

        String host = hostEnd < 0 ? remainder : remainder.substring(0, hostEnd);

        return host.toLowerCase(Locale.ROOT);
    }

    private int indexOfFirst(String value, char... candidates) {

        int best = -1;

        for (char candidate : candidates) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }

        return best;
    }
}
