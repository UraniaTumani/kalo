package com.kalo.auth.service;

import com.kalo.auth.dto.ForgotPasswordRequest;
import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.dto.PasswordResetQueueItem;
import com.kalo.auth.dto.ResetPasswordRequest;
import com.kalo.auth.entity.PasswordResetRequest;
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
 * Password recovery, verified by a person.
 *
 * The shape of this is forced by what KALO actually has. There is no SMS
 * provider and no mail provider, and {@code phone_verified} is written false at
 * registration and never set true outside the development seeder — so the
 * phone on an account is an unverified claim, and there is no channel at all
 * over which a code could be delivered. Every self-service design therefore
 * reduces to "whoever knows the number wins".
 *
 * So the verification is a telephone call made by an administrator, and the
 * code is minted only once a human has taken responsibility for the identity.
 * That does not scale, and it is not meant to: it is proportionate to a closed
 * pilot, and {@link ResetDeliveryChannel} is where an SMS provider slots in
 * later without any of this changing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * No 0/O, 1/I/L, 5/S, 2/Z — the code is read down a telephone line, and a
     * character the listener has to guess at is an attempt wasted.
     */
    private static final char[] ALPHABET =
            "ABCDEFGHJKMNPQRTUVWXY346789".toCharArray();

    private static final int CODE_LENGTH = 8;

    /** Long enough to finish the call and type it, short enough to matter. */
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(15);

    /**
     * Eight characters of this alphabet is about forty bits, so an online
     * guess is the realistic attack rather than an offline one. Five tries and
     * the request is burnt, which makes the expected cost of guessing absurd
     * while leaving room for a misheard character.
     */
    private static final int MAX_ATTEMPTS = 5;

    /** Per account, whatever address the requests arrive from. */
    private static final int MAX_REQUESTS_PER_WINDOW = 3;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);

    private final PasswordResetRequestRepository resetRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ResetDeliveryChannel deliveryChannel;

    @Override
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());
        Instant now = Instant.now();

        Optional<User> found = userRepository.findByPhone(phone);

        if (found.isEmpty()) {
            /*
             * Nothing to record and nothing to say. The caller gets the same
             * acknowledgement either way, which is the only thing that stops
             * this endpoint answering "is this number registered?".
             */
            log.info("Password reset requested for an unknown number");
            return;
        }

        User user = found.get();

        if (user.getStatus() == UserStatus.SUSPENDED) {
            /*
             * Suspended accounts cannot recover. Recorded rather than dropped,
             * so that an administrator seeing repeated attempts against a
             * suspended account has the evidence — but never surfaced to the
             * caller, since a refusal they could detect would tell them the
             * account exists and is suspended.
             */
            PasswordResetRequest refused = new PasswordResetRequest();
            refused.setUser(user);
            refused.setStatus(PasswordResetStatus.REJECTED);
            refused.setCompletedAt(now);
            resetRepository.save(refused);

            log.warn(
                    "Password reset refused for suspended account: userId={}",
                    user.getId()
            );
            return;
        }

        long recent = resetRepository.countRecentForUser(
                user.getId(),
                now.minus(REQUEST_WINDOW)
        );

        if (recent >= MAX_REQUESTS_PER_WINDOW) {
            /*
             * Silently. Telling the caller they are throttled would confirm the
             * account exists, and the throttle exists to keep somebody from
             * flooding the queue against one victim from many addresses until
             * a tired administrator waves one through.
             */
            log.warn(
                    "Password reset throttled for userId={} ({} in the last hour)",
                    user.getId(),
                    recent
            );
            return;
        }

        /* One live recovery per account: asking again supersedes. */
        resetRepository.rejectAllOpenForUser(user.getId(), now);

        PasswordResetRequest pending = new PasswordResetRequest();
        pending.setUser(user);
        pending.setStatus(PasswordResetStatus.PENDING);

        resetRepository.save(pending);

        deliveryChannel.onRequested(user, pending);

        log.info(
                "Password reset queued for review: userId={} requestId={}",
                user.getId(),
                pending.getId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PasswordResetQueueItem> pendingRequests(Pageable pageable) {

        return resetRepository
                .findAllByStatusOrderByCreatedAtAsc(
                        PasswordResetStatus.PENDING,
                        pageable
                )
                .map(request -> {

                    User user = request.getUser();

                    return new PasswordResetQueueItem(
                            request.getId(),
                            user.getFirstName(),
                            user.getLastName(),
                            user.getPhone(),
                            user.getRole().name(),
                            user.getStatus().name(),
                            request.getCreatedAt()
                    );
                });
    }

    @Override
    @Transactional
    public IssuedResetCodeResponse issueCode(Long requestId) {

        PasswordResetRequest request = resetRepository
                .findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Password reset request not found"
                ));

        if (request.getStatus() != PasswordResetStatus.PENDING) {
            throw new InvalidOperationException(
                    "This request has already been handled"
            );
        }

        /*
         * Re-checked at issue, not only at request. A suspension between the
         * two is exactly the case this guards: the queue entry was legitimate
         * when it was made and would otherwise still be approvable.
         */
        if (request.getUser().getStatus() == UserStatus.SUSPENDED) {
            request.setStatus(PasswordResetStatus.REJECTED);
            request.setCompletedAt(Instant.now());
            resetRepository.save(request);

            throw new InvalidOperationException(
                    "This account is suspended and cannot be recovered"
            );
        }

        User admin = currentAdmin();

        String code = generateCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(CODE_LIFETIME);

        request.setCodeHash(passwordEncoder.encode(code));
        request.setStatus(PasswordResetStatus.ISSUED);
        request.setExpiresAt(expiresAt);
        request.setAttempts(0);
        request.setIssuedBy(admin);
        request.setIssuedAt(now);

        resetRepository.save(request);

        log.info(
                "Password reset code issued: requestId={} userId={} byAdminId={}",
                request.getId(),
                request.getUser().getId(),
                admin.getId()
        );

        deliveryChannel.onCodeIssued(request.getUser(), request, code);

        /*
         * The only time the raw code leaves this method.
         *
         * It goes to the administrator who has just identified the caller, to
         * be read down the telephone. Once a channel exists that can reach the
         * account holder directly, onCodeIssued above becomes the delivery and
         * this field stops being returned at all.
         */
        return new IssuedResetCodeResponse(request.getId(), code, expiresAt);
    }

    @Override
    @Transactional
    public void rejectRequest(Long requestId) {

        PasswordResetRequest request = resetRepository
                .findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Password reset request not found"
                ));

        if (request.getStatus() == PasswordResetStatus.USED) {
            throw new InvalidOperationException(
                    "This request has already been used"
            );
        }

        /* Resolved before the write, so a failed lookup changes nothing. */
        User admin = currentAdmin();

        request.setStatus(PasswordResetStatus.REJECTED);
        request.setCompletedAt(Instant.now());
        resetRepository.save(request);

        log.info(
                "Password reset refused: requestId={} userId={} byAdminId={}",
                requestId,
                request.getUser().getId(),
                admin.getId()
        );
    }

    /**
     * noRollbackFor is load-bearing, not tidiness.
     *
     * Every failure below leaves by throwing, and a throw inside a transaction
     * rolls it back — which silently undid the one write that matters on the
     * failure path, the attempt counter. The cap read as if it worked and
     * enforced nothing: five wrong codes cost an attacker nothing, and a
     * forty-bit code with unlimited guesses is not a secret. Found by
     * PasswordResetIntegrationTest.wrongCodeBurnsAfterFiveAttempts, which
     * failed because the real code still worked afterwards.
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
                        PasswordResetStatus.ISSUED
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

                log.warn(
                        "Password reset burnt after {} wrong codes: userId={}",
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
                "Password reset completed: userId={} sessionsRevoked={}",
                user.getId(),
                dropped
        );
    }

    /**
     * One refusal for every way this can fail.
     *
     * Unknown number, no live request, wrong code, expired code, burnt
     * request, suspended account — all the same sentence. The distinctions are
     * real but every one of them is a fact about an account, and the person who
     * benefits from learning it is not the one who forgot their password.
     */
    private static InvalidOperationException refused() {
        return new InvalidOperationException(
                "This reset code is not valid or has expired"
        );
    }

    /**
     * The administrator making the decision.
     *
     * Resolved here rather than passed in from the controller, matching how
     * the rest of the application reads the caller, and recorded on the
     * request: every issued code names whoever vouched for the identity, which
     * is the only accountability a manual verification has.
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

    private String generateCode() {

        StringBuilder code = new StringBuilder(CODE_LENGTH);

        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }

        return code.toString();
    }
}
