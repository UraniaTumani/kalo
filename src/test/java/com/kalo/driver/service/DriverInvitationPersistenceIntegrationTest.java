package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class DriverInvitationPersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationPersistence persistence;
    @Autowired DriverInvitationRepository invitations;
    @Autowired PlatformTransactionManager transactionManager;
    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commitsReplacementWithOnlyHashAndRevokesPreviousUnusedInvitation() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = driver(company);
        DriverInvitation previous = oldInvitation(driver, hash());
        authenticate(company);
        String raw = codec.generate();
        String digest = codec.digest(raw);
        Long id = stage(driver, digest);
        assertThat(invitations.findById(previous.getId()).orElseThrow().getRevokedAt()).isNotNull();
        DriverInvitation saved = invitations.findById(id).orElseThrow();
        assertThat(saved.getTokenHash()).isEqualTo(digest).isNotEqualTo(raw);
        assertThat(saved.getExpiresAt()).isAfter(saved.getCreatedAt());
        assertThat(invitations.count()).isEqualTo(2);
    }

    @Test
    void failedInsertRollsBackPreviousRevocation() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = driver(company);
        String duplicate = hash();
        DriverInvitation previous = oldInvitation(driver, duplicate);
        authenticate(company);
        assertThatThrownBy(() -> stage(driver, duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(invitations.findById(previous.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(invitations.count()).isEqualTo(1);
    }

    @Test
    void callerFailureRollsBackBothInsertAndRevocation() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = driver(company);
        DriverInvitation previous = oldInvitation(driver, hash());
        authenticate(company);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            persistence.stageHash(driver.getId(), hash());
            throw new IllegalStateException("Delivery preparation failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Delivery preparation failed");
        assertThat(invitations.findById(previous.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(invitations.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsForSameDriverCommitOnlyOneInvitation() throws Exception {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = driver(company);
        assertRace(company, driver, driver);
        assertThat(invitations.count()).isEqualTo(1);
    }

    @Test
    void concurrentDriversCannotExceedSharedCompanyQuota() throws Exception {
        TaxiCompany company = fixtures.approvedCompany();
        Driver history = driver(company);
        for (int i = 0; i < 19; i++) {
            oldInvitation(history, hash());
        }
        assertRace(company, driver(company), driver(company));
        assertThat(invitations.count()).isEqualTo(20);
    }

    @Test
    void refusesPersistenceWithoutCallerTransaction() {
        assertThatThrownBy(() -> persistence.stageHash(1L, hash()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Long stage(Driver driver, String digest) {
        return new TransactionTemplate(transactionManager).execute(
                status -> persistence.stageHash(driver.getId(), digest));
    }

    private void assertRace(TaxiCompany company, Driver first, Driver second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        // Resolve the phone on the test thread, never share lazy entities with workers.
        String phone = company.getOwner().getPhone();
        try {
            var one = executor.submit(() -> attempt(phone, first.getId(), ready, start));
            var two = executor.submit(() -> attempt(phone, second.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("committed", "limited");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String attempt(String phone, Long driverId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        authenticate(phone);
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Race did not start");
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> persistence.stageHash(driverId, hash()));
            return "committed";
        } catch (InvalidOperationException limited) {
            assertThat(limited.getMessage()).isIn(
                    "Driver invitation cooldown is active", "Company invitation limit reached");
            return "limited";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Driver driver(TaxiCompany company) {
        return fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
    }

    private DriverInvitation oldInvitation(Driver driver, String digest) {
        DriverInvitation invitation = new DriverInvitation();
        invitation.setDriver(driver);
        invitation.setCompany(driver.getCompany());
        invitation.setTokenHash(digest);
        invitation.setExpiresAt(Instant.now().plusSeconds(3600));
        DriverInvitation saved = invitations.saveAndFlush(invitation);
        jdbcTemplate.update("UPDATE driver_invitations SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), saved.getId());
        return saved;
    }

    private String hash() {
        return codec.digest(codec.generate());
    }

    private void authenticate(TaxiCompany company) {
        authenticate(company.getOwner().getPhone());
    }

    private void authenticate(String phone) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(phone, "unused",
                        AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
    }
}
