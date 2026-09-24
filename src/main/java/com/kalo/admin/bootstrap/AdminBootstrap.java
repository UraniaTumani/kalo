package com.kalo.admin.bootstrap;

import com.kalo.common.util.PhoneNumberNormalizer;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, because nothing else can.
 *
 * Every administrative endpoint sits behind {@code hasRole("ADMIN")}, which
 * leaves an obvious hole in the middle: there is no way to get the first one.
 * Until now the answer was an UPDATE statement run by hand against production,
 * which is not a procedure so much as an invitation to get it wrong at the
 * worst moment — and it silently blocked the password-recovery flow, whose
 * whole identity check is an administrator making a telephone call.
 *
 * This deliberately reads configuration rather than exposing an endpoint. An
 * unauthenticated "create an administrator" route is a catastrophe the first
 * time somebody forgets to remove it, and no amount of one-time-token
 * machinery makes that risk worth taking. Setting an environment variable
 * already requires access to the deployment, which is the same level of trust
 * as reaching the database — so this grants nothing that was not already
 * granted, and leaves no surface behind afterwards.
 *
 * It runs on every start and does nothing at all once an administrator exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap {

    /** Matches the minimum the registration endpoints enforce. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap.phone:}")
    private String phone;

    @Value("${app.admin.bootstrap.password:}")
    private String password;

    @Value("${app.admin.bootstrap.first-name:KALO}")
    private String firstName;

    @Value("${app.admin.bootstrap.last-name:Administrator}")
    private String lastName;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createFirstAdministratorIfMissing() {

        if (isBlank(phone) && isBlank(password)) {
            /*
             * Not configured, which is the normal state of a running system.
             * Silent on purpose: warning on every start about something nobody
             * needs teaches people to ignore the log.
             */
            return;
        }

        if (isBlank(phone) || isBlank(password)) {
            log.error(
                    "Administrator bootstrap is half configured: "
                            + "both app.admin.bootstrap.phone and .password are needed. "
                            + "No account was created."
            );
            return;
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.error(
                    "Administrator bootstrap password is shorter than {} characters. "
                            + "No account was created.",
                    MIN_PASSWORD_LENGTH
            );
            return;
        }

        if (userRepository.existsByRole(UserRole.ADMIN)) {
            /*
             * The common case on every restart after the first. Logged so that
             * somebody who has set the variables expecting a new account can
             * see why they did not get one, rather than assuming it worked.
             */
            log.info(
                    "Administrator bootstrap skipped: an administrator already exists. "
                            + "Create further administrators through the admin API."
            );
            return;
        }

        String normalised = PhoneNumberNormalizer.normalize(phone);

        if (userRepository.existsByPhone(normalised)) {
            /*
             * Refused rather than promoted. Quietly turning somebody's existing
             * account into an administrator — or worse, overwriting the
             * password on it — is not something a startup hook should ever do
             * on the strength of a configuration value.
             */
            log.error(
                    "Administrator bootstrap refused: the configured phone already "
                            + "belongs to an account. Promote it deliberately, or use "
                            + "a number that is not yet registered."
            );
            return;
        }

        User admin = new User();

        admin.setFirstName(firstName.trim());
        admin.setLastName(lastName.trim());
        admin.setPhone(normalised);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPhoneVerified(false);

        User saved = userRepository.save(admin);

        /*
         * The identifier and nothing else. A password that reaches a log has
         * reached every aggregator, terminal scrollback and support ticket that
         * log is ever pasted into.
         */
        log.warn(
                "Created the first administrator (userId={}) from configuration. "
                        + "Sign in, then clear app.admin.bootstrap.password from the "
                        + "environment so the credential does not outlive its purpose.",
                saved.getId()
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
