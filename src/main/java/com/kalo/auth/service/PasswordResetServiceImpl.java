package com.kalo.auth.service;

import com.kalo.auth.dto.AdminFallbackResetRequest;
import com.kalo.auth.dto.FallbackResetAuditItem;
import com.kalo.auth.dto.ForgotPasswordRequest;
import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.dto.ResetPasswordRequest;
import com.kalo.auth.entity.PasswordResetRequest;
import com.kalo.auth.enums.PasswordResetChannel;
import com.kalo.auth.enums.PasswordResetStatus;
import com.kalo.auth.repository.PasswordResetRequestRepository;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.common.util.PhoneNumberNormalizer;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Password recovery by one-time code.
 *
 * The code goes to the number on the account, which is the change that makes
 * this self-service. The previous design could not do that — there was no
 * provider and no channel — so an administrator telephoned the account holder
 * and read a code aloud, and that does not scale past a pilot.
 *
 * What the SMS actually proves is worth being precise about, because it is not
 * what a verified phone number would prove. {@code phone_verified} is still
 * written false at registration and nothing sets it true, so the number on an
 * account is an unverified claim. A code sent to it therefore proves possession
 * of that SIM right now — which is strictly stronger than anything KALO could
 * check before, and strictly weaker than a number verified at signup. The
 * honest description is that recovery is now as strong as the account holder's
 * control of the number they gave us.
 *
 * Three things guard the endpoint, and the second and third are about money
 * rather than access:
 *
 *   - the code is six digits, so the attempt cap is load-bearing rather than
 *     tidy: twenty bits with unlimited guesses is not a secret;
 *   - a per-account cooldown and daily cap, because an unauthenticated endpoint
 *     that sends a text message is an unauthenticated endpoint that spends
 *     money and rings a stranger's phone;
 *   - a deployment-wide ceiling, against SMS pumping: farming traffic to
 *     expensive ranges through whatever endpoint will send it is a real and
 *     well-paid business, and a recovery form is a standard way in.
 *
 * Every refusal in {@link #requestReset} is silent and indistinguishable, which
 * is why none of them throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Six digits, because it arrives in a text message and gets typed on a
     * phone — and because every OTP anybody has ever received is six digits,
     * which is worth more than the entropy an unfamiliar format would add.
     *
     * A million possibilities is not much. What makes it safe is everything
     * around it: five attempts, five minutes, bcrypt at rest, and a cooldown
     * that stops a caller cycling through fresh codes. Remove any one of those
     * and this number becomes the problem.
     */
    private static final int OTP_LENGTH = 6;

    private static final Duration OTP_LIFETIME = Duration.ofMinutes(5);

    /**
     * The fallback code is read down a telephone line, so it avoids characters
     * a listener has to guess at: no 0/O, 1/I/L, 5/S or 2/Z.
     */
    private static final char[] SPOKEN_ALPHABET =
            "ABCDEFGHJKMNPQRTUVWXY346789".toCharArray();

    private static final int FALLBACK_CODE_LENGTH = 8;

    /**
     * Longer than the SMS code, for two reasons that point the same way: a code
     * read aloud has to survive the rest of a telephone call before anybody
     * types it, and eight characters of this alphabet is about forty bits rather
     * than twenty — so the wider window is a smaller risk than the five-minute
     * one it replaces.
     */
    private static final Duration FALLBACK_LIFETIME = Duration.ofMinutes(15);

    /**
     * Five guesses, then the code is burnt.
     *
     * This is the defence, not a courtesy. Six digits is a million
     * possibilities; at five guesses per code an attacker needs on the order of
     * a hundred thousand fresh codes to expect one hit, and the cooldown and
     * daily cap make that many impossible to obtain. Raise this number and the
     * arithmetic stops working.
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * How soon an account may ask again.
     *
     * The resend button on the page counts down from this. Enforced here as
     * well because a client-side timer stops an impatient person, not somebody
     * calling the endpoint directly — and each send is a message somebody
     * receives whether they asked for it or not.
     */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Per account, whatever address the requests arrive from. */
    private static final int MAX_REQUESTS_PER_DAY = 5;
    private static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final PasswordResetRequestRepository resetRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher events;

    /**
     * The most recovery messages this deployment will send in any 24 hours.
     *
     * A rolling window rather than a calendar day, so there is no midnight at
     * which the budget resets and an attacker can start again.
     *
     * The default is sized for a pilot: a few hundred users forget a password a
     * handful of times a month between them, so five hundred a day is orders of
     * magnitude above normal use and still a bounded bill. It should be raised
     * deliberately, with a number somebody has looked at, rather than by
     * whoever first hits it.
     */
    @Value("${app.sms.daily-limit:500}")
    private int dailySmsLimit;

    /**
     * Nothing here throws, and every early return is a refusal the caller
     * cannot tell apart from success. That is the whole security property of
     * this method: the endpoint is reachable without signing in, so any
     * distinction it exposes — registered, suspended, throttled — is a fact
     * about somebody else's account given to whoever asked.
     */
    @Override
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());
        Instant now = Instant.now();

        Optional<User> found = userRepository.findByPhone(phone);

        if (found.isEmpty()) {
            /*
             * Nothing to record and nothing to say. Recording an attempt
             * against a number belonging to nobody would also hand anyone a way
             * to fill this table.
             */
            log.info("Password reset requested for an unknown number");
            return;
        }

        User user = found.get();

        /*
         * The two account-level bounds come before the suspension check, and the
         * order is deliberate.
         *
         * A suspended account is refused by recording a row, so that repeated
         * attempts against it are visible to an administrator. Checking the
         * suspension first would mean one row per request with nothing bounding
         * it: the per-IP limit does not help, because the attempts can be spread
         * across addresses, and the table would be fillable by anybody who knows
         * one suspended number. Behind these two checks the same evidence is
         * recorded at no more than one row a minute and five a day, which is
         * ample for noticing a pattern.
         *
         * Nothing is leaked by the order. Every one of these paths returns
         * silently after the same fixed delay.
         */
        if (withinCooldown(user.getId(), now)) {
            log.info("Password reset within cooldown: userId={}", user.getId());
            return;
        }

        long recent = resetRepository.countRecentForUser(
                user.getId(),
                now.minus(DAILY_WINDOW)
        );

        if (recent >= MAX_REQUESTS_PER_DAY) {
            log.warn(
                    "Password reset daily cap reached: userId={} ({} in 24h)",
                    user.getId(),
                    recent
            );
            return;
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            /*
             * Recorded rather than dropped, so an administrator seeing repeated
             * attempts against a suspended account has the evidence. No code and
             * no expiry, which is also what keeps this row from counting against
             * an SMS budget it never spent.
             */
            PasswordResetRequest refused = new PasswordResetRequest();
            refused.setUser(user);
            refused.setStatus(PasswordResetStatus.REJECTED);
            refused.setChannel(PasswordResetChannel.SMS);
            refused.setCompletedAt(now);
            resetRepository.save(refused);

            log.warn(
                    "Password reset refused for suspended account: userId={}",
                    user.getId()
            );
            return;
        }

        long sentRecently = resetRepository.countMessagesSentSince(
                PasswordResetChannel.SMS,
                now.minus(DAILY_WINDOW)
        );

        if (sentRecently >= dailySmsLimit) {
            /*
             * ERROR rather than WARN: this one is not somebody misbehaving
             * against one account, it is the whole deployment out of budget, and
             * from this moment nobody can recover by SMS. It wants a human.
             */
            log.error(
                    "SMS daily ceiling reached ({} of {}): no recovery codes will be sent "
                            + "until the window rolls. Recovery requires the admin fallback.",
                    sentRecently,
                    dailySmsLimit
            );
            return;
        }

        /* One live code per account: asking again supersedes. */
        resetRepository.rejectAllOpenForUser(user.getId(), now);

        String code = digits(OTP_LENGTH);
        Instant expiresAt = now.plus(OTP_LIFETIME);

        PasswordResetRequest active = new PasswordResetRequest();
        active.setUser(user);
        active.setStatus(PasswordResetStatus.ACTIVE);
        active.setChannel(PasswordResetChannel.SMS);
        active.setCodeHash(passwordEncoder.encode(code));
        active.setExpiresAt(expiresAt);
        active.setAttempts(0);

        resetRepository.save(active);

        /*
         * Published rather than sent. The listener runs after this transaction
         * commits and on another thread, both of which are required: a message
         * about a code that was rolled back would be worse than no message, and
         * a provider call on this thread would blow through the constant-time
         * floor in the controller and turn the endpoint back into an oracle for
         * which numbers are registered.
         */
        events.publishEvent(new PasswordResetCodeIssued(
                user.getId(),
                user.getPhone(),
                code,
                expiresAt
        ));

        log.info(
                "Recovery code created: userId={} requestId={} expiresAt={}",
                user.getId(),
                active.getId(),
                expiresAt
        );
    }

    /**
     * noRollbackFor is load-bearing, not tidiness.
     *
     * Every failure below leaves by throwing, and a throw inside a transaction
     * rolls it back — which silently undid the one write that matters on the
     * failure path, the attempt counter. The cap read as if it worked and
     * enforced nothing. That was survivable when the code was forty bits; with a
     * six-digit code it would mean a million free guesses against a live
     * account, so this annotation is now the difference between a working OTP
     * and a decorative one.
     *
     * The refusals write nothing else, so keeping the transaction is safe: the
     * only state they produce is the record that a guess was made.
     */
    @Override
    @Transactional(noRollbackFor = InvalidOperationException.class)
    public void resetPassword(ResetPasswordRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());
        Instant now = Instant.now();

        User user = userRepository
                .findByPhone(phone)
                .orElseThrow(PasswordResetServiceImpl::refused);

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw refused();
        }

        PasswordResetRequest reset = resetRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                        user.getId(),
                        PasswordResetStatus.ACTIVE
                )
                .orElseThrow(PasswordResetServiceImpl::refused);

        if (!reset.isRedeemable(now)) {
            throw refused();
        }

        if (!passwordEncoder.matches(request.code(), reset.getCodeHash())) {

            reset.setAttempts(reset.getAttempts() + 1);

            if (reset.getAttempts() >= MAX_ATTEMPTS) {
                reset.setStatus(PasswordResetStatus.REJECTED);
                reset.setCompletedAt(now);
                reset.setCodeHash(null);

                log.warn(
                        "Recovery code burnt after {} wrong guesses: userId={}",
                        reset.getAttempts(),
                        user.getId()
                );
            }

            resetRepository.save(reset);

            throw refused();
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        reset.setStatus(PasswordResetStatus.USED);
        reset.setCompletedAt(now);
        reset.setCodeHash(null);
        resetRepository.save(reset);

        /*
         * Whoever else was holding a session on this account loses it. A
         * recovery is most often a recovery *from* somebody, and leaving their
         * refresh token alive would hand the account straight back.
         */
        int dropped = refreshTokenService.revokeAllForUser(user.getId());

        log.info(
                "Password reset completed: userId={} channel={} sessionsRevoked={}",
                user.getId(),
                reset.getChannel(),
                dropped
        );
    }

    /**
     * The narrow exception: a code issued by hand.
     *
     * SMS recovery has one failure it cannot design away — a number that no
     * longer reaches anybody. Without this, losing a SIM means permanently
     * losing an account and everything in it, which is not a trade worth making
     * for a taxi platform. With it there is a path that does not require the
     * phone, and the whole risk of the feature is that the path becomes routine.
     *
     * So it is kept narrow deliberately: administrators only, a mandatory
     * written justification, its own channel value, its own audit listing, and a
     * WARN on every use. It does not weaken the OTP rules — the code it issues
     * is redeemed through the same endpoint, with the same attempt cap, the same
     * single use and the same bcrypt-only storage. The one thing it skips is the
     * message.
     */
    @Override
    @Transactional
    public IssuedResetCodeResponse issueFallbackCode(AdminFallbackResetRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());

        /*
         * This one does say whether the account exists. Concealing it would be
         * theatre: the caller is an authenticated administrator who can list
         * every user on the next endpoint along.
         */
        User user = userRepository
                .findByPhone(phone)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account is registered with that number"
                ));

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new InvalidOperationException(
                    "This account is suspended and cannot be recovered"
            );
        }

        User admin = currentAdmin();
        Instant now = Instant.now();

        /* Supersedes any live SMS code, so the account still has exactly one. */
        resetRepository.rejectAllOpenForUser(user.getId(), now);

        String code = spokenCode();
        Instant expiresAt = now.plus(FALLBACK_LIFETIME);

        PasswordResetRequest issued = new PasswordResetRequest();
        issued.setUser(user);
        issued.setStatus(PasswordResetStatus.ACTIVE);
        issued.setChannel(PasswordResetChannel.ADMIN_FALLBACK);
        issued.setCodeHash(passwordEncoder.encode(code));
        issued.setExpiresAt(expiresAt);
        issued.setAttempts(0);
        issued.setIssuedBy(admin);
        issued.setIssuedAt(now);
        issued.setVerificationNote(request.verificationNote().trim());

        resetRepository.save(issued);

        /*
         * WARN, every time. An administrator handing somebody a way into an
         * account they cannot otherwise reach is exactly the line in a log that
         * should stand out, and the note is in the row rather than here because
         * a log is not a record anybody reviews.
         */
        log.warn(
                "Recovery code issued by hand: requestId={} userId={} byAdminId={} expiresAt={}",
                issued.getId(),
                user.getId(),
                admin.getId(),
                expiresAt
        );

        /*
         * The only response in the application that carries a live code, and it
         * goes to the administrator on the call. Nothing keeps it: only the
         * bcrypt hash is stored, so losing this means issuing another.
         */
        return new IssuedResetCodeResponse(issued.getId(), code, expiresAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FallbackResetAuditItem> fallbackAudit(Pageable pageable) {

        return resetRepository
                .findAllByChannelOrderByCreatedAtDesc(
                        PasswordResetChannel.ADMIN_FALLBACK,
                        pageable
                )
                .map(request -> {

                    User person = request.getUser();
                    User admin = request.getIssuedBy();

                    return new FallbackResetAuditItem(
                            request.getId(),
                            person.getFirstName() + " " + person.getLastName(),
                            person.getPhone(),
                            admin == null
                                    ? null
                                    : admin.getFirstName() + " " + admin.getLastName(),
                            request.getVerificationNote(),
                            request.getIssuedAt(),
                            request.getStatus().name()
                    );
                });
    }

    /**
     * Whether this account was sent a code too recently to send another.
     *
     * Measured from the last request of any kind rather than the last successful
     * send, so a refused attempt also holds the door — otherwise the cooldown
     * would be skippable by whatever made the previous one fail.
     */
    private boolean withinCooldown(Long userId, Instant now) {

        return resetRepository
                .findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(previous -> previous.getCreatedAt() != null
                        && previous.getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN)))
                .orElse(false);
    }

    /**
     * One refusal for every way a redemption can fail.
     *
     * Unknown number, no live code, wrong code, expired code, burnt request,
     * suspended account — all the same sentence. The distinctions are real but
     * every one of them is a fact about an account, and the person who benefits
     * from learning it is not the one who forgot their password.
     */
    private static InvalidOperationException refused() {
        return new InvalidOperationException(
                "This code is not valid or has expired"
        );
    }

    /**
     * The administrator making the decision.
     *
     * Resolved here rather than passed in from the controller, matching how the
     * rest of the application reads the caller, and recorded on the request:
     * every hand-issued code names whoever vouched for the identity, which is
     * the only accountability a manual verification has.
     */
    private User currentAdmin() {

        String phone = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        return userRepository
                .findByPhone(phone)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
    }

    /**
     * A uniformly distributed decimal string, leading zeros included.
     *
     * A digit at a time rather than one bounded integer formatted to width,
     * because "%06d" on nextInt(1_000_000) is correct but invites somebody to
     * later change it to nextInt(900_000) + 100_000 to "avoid leading zeros" —
     * which throws away a tenth of the keyspace to make the code look tidier.
     */
    private String digits(int length) {

        StringBuilder code = new StringBuilder(length);

        for (int index = 0; index < length; index++) {
            code.append((char) ('0' + RANDOM.nextInt(10)));
        }

        return code.toString();
    }

    private String spokenCode() {

        StringBuilder code = new StringBuilder(FALLBACK_CODE_LENGTH);

        for (int index = 0; index < FALLBACK_CODE_LENGTH; index++) {
            code.append(SPOKEN_ALPHABET[RANDOM.nextInt(SPOKEN_ALPHABET.length)]);
        }

        return code.toString();
    }
}
