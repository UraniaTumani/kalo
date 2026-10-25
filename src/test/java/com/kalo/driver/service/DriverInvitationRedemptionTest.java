package com.kalo.driver.service;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.auth.security.DriverAccountAccessPolicy;
import com.kalo.driver.repository.*;
import com.kalo.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationRedemptionTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private DriverInvitationRedemption service(boolean enabled) {
        return new DriverInvitationRedemption(jdbc,mock(EntityManager.class),mock(DriverRepository.class),
                mock(DriverInvitationRepository.class),mock(DriverInvitationDeliveryRepository.class),mock(UserRepository.class),
                mock(PasswordEncoder.class),mock(DriverInvitationEligibilityPolicy.class),new DriverInvitationTokenCodec(),enabled,false,mock(DriverAccountAccessPolicy.class));
    }
    @Test void disabledByDefaultPathDoesNoWork() {
        assertThatThrownBy(()->service(false).redeemToPendingAccount(null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service(true).activate(null,null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void validatesPasswordBeforeDatabaseAndRejectsBcryptByteOverflow() {
        var codec=new DriverInvitationTokenCodec(); String token=codec.generate();
        for(String password:new String[]{null,"","short","é".repeat(37)}) {
            assertThatThrownBy(()->service(true).redeemToPendingAccount(token,password)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }
    @Test void malformedTokenIsRejectedWithoutEchoingItOrQueryingDatabase() {
        for(String token:new String[]{null,"secret","!".repeat(43)}) {
            assertThatThrownBy(()->service(true).redeemToPendingAccount(token,"TestPass123!"))
                    .isInstanceOf(InvalidOperationException.class).hasMessage("Invalid driver invitation");
        }
        verifyNoInteractions(jdbc);
    }
}
