package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.auth.security.DriverAccountAccessPolicy;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Internal single-use redemption foundation, disabled by default and with NO API.
 * The foundation method creates PENDING accounts. Final activation is separately
 * disabled by default; public endpoint rate limits and rollout remain separate gates.
 * Lock order matches delivery: company -> driver -> queue -> invitation.
 * No IDs or phone supplied by the caller are used to select account ownership.
 */
@Service
public class DriverInvitationRedemption {
    private record Target(long invitationId,long companyId,long driverId,Long deliveryId) {}
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final DriverRepository drivers;
    private final DriverInvitationRepository invitations;
    private final DriverInvitationDeliveryRepository deliveries;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final DriverInvitationEligibilityPolicy eligibility;
    private final DriverInvitationTokenCodec codec;
    private final boolean enabled;
    private final boolean activationEnabled;
    private final DriverAccountAccessPolicy access;
    public DriverInvitationRedemption(JdbcTemplate jdbc,EntityManager entities,DriverRepository drivers,
            DriverInvitationRepository invitations,DriverInvitationDeliveryRepository deliveries,
            UserRepository users,PasswordEncoder passwords,DriverInvitationEligibilityPolicy eligibility,
            DriverInvitationTokenCodec codec,
            @Value("${app.driver-invitation.redemption-enabled:false}") boolean enabled,
            @Value("${app.driver-invitation.activation-enabled:false}") boolean activationEnabled,
            DriverAccountAccessPolicy access) {
        this.jdbc=jdbc;this.entities=entities;this.drivers=drivers;this.invitations=invitations;
        this.deliveries=deliveries;this.users=users;this.passwords=passwords;
        this.eligibility=eligibility;this.codec=codec;this.enabled=enabled;
        this.activationEnabled=activationEnabled;this.access=access;
    }
    @Transactional
    public Long redeemToPendingAccount(String token,String password) {
        if (!enabled) throw new IllegalStateException("Driver invitation redemption is disabled");
        return consume(token,password,false);
    }
    @Transactional
    public Long activate(String token,String password) {
        if (!activationEnabled) throw new IllegalStateException("Driver invitation activation is disabled");
        return consume(token,password,true);
    }
    private Long consume(String token,String password,boolean activate) {
        if (password==null || password.isBlank() || password.length()<8 || password.length()>72
                || password.getBytes(StandardCharsets.UTF_8).length>72) {
            throw new IllegalArgumentException("Password must contain at least 8 characters and at most 72 UTF-8 bytes");
        }
        String hash;
        try { hash=codec.digest(token); } catch (IllegalArgumentException invalid) { throw invalid(); }
        var matches=jdbc.query("""
                SELECT i.id,i.company_id,i.driver_id,d.id AS delivery_id
                FROM driver_invitations i LEFT JOIN driver_invitation_deliveries d ON d.invitation_id=i.id
                WHERE i.token_hash=?
                """,(rs,row)->new Target(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,Long.class)),hash);
        if (matches.size()!=1 || matches.get(0).deliveryId()==null) throw invalid();
        var target=matches.get(0);
        var company=entities.find(TaxiCompany.class,target.companyId(),LockModeType.PESSIMISTIC_WRITE);
        if (company==null) throw invalid();
        entities.refresh(company,LockModeType.PESSIMISTIC_WRITE);
        var driver=drivers.findForUpdateByIdAndCompanyId(target.driverId(),target.companyId()).orElseThrow(DriverInvitationRedemption::invalid);
        entities.refresh(driver,LockModeType.PESSIMISTIC_WRITE);
        var queue=deliveries.findForUpdateById(target.deliveryId()).orElseThrow(DriverInvitationRedemption::invalid);
        entities.refresh(queue,LockModeType.PESSIMISTIC_WRITE);
        var invitation=entities.find(DriverInvitation.class,target.invitationId(),LockModeType.PESSIMISTIC_WRITE);
        if (invitation==null) throw invalid();
        entities.refresh(invitation,LockModeType.PESSIMISTIC_WRITE);
        Instant now=Instant.now();
        if (!hash.equals(invitation.getTokenHash())
                || !invitation.getCompany().getId().equals(target.companyId())
                || !invitation.getDriver().getId().equals(driver.getId())
                || !queue.getInvitation().getId().equals(invitation.getId()) || !invitation.isRedeemable(now)
                || invitation.getIssuedByUserId()==null || !queue.getDestination().equals(driver.getPhone())
                || users.existsByPhone(driver.getPhone())) throw invalid();
        if (activate && ((queue.getState()!=DriverInvitationDelivery.State.ACCEPTED
                && queue.getState()!=DriverInvitationDelivery.State.REVIEW)
                || !DriverInvitationMessageId.create(queue.getDestination(),hash,"driver-invitation-"+invitation.getId())
                        .equals(queue.getProviderMessageId()))) throw invalid();
        try { eligibility.validate(driver,company); } catch (InvalidOperationException rejected) { throw invalid(); }
        User user=new User(); user.setFirstName(driver.getFirstName()); user.setLastName(driver.getLastName());
        user.setPhone(driver.getPhone()); user.setRole(UserRole.DRIVER); user.setStatus(activate ? UserStatus.ACTIVE : UserStatus.PENDING);
        user.setPhoneVerified(activate); user.setPasswordHash(passwords.encode(password));
        try {
            users.saveAndFlush(user);
            driver.setUser(user); drivers.saveAndFlush(driver);
            if (activate && !access.canAuthenticate(user)) throw invalid();
            Instant redeemedAt=Instant.now();
            if (!invitation.isRedeemable(redeemedAt)) throw invalid();
            invitation.setRedeemedAt(redeemedAt); invitations.saveAndFlush(invitation);
            queue.cancel(); deliveries.flush();
        } catch (DataIntegrityViolationException collision) {
            // A concurrent registration may win the phone constraint; never promote/link that user.
            throw invalid();
        }
        return user.getId();
    }
    private static InvalidOperationException invalid() { return new InvalidOperationException("Invalid driver invitation"); }
}
