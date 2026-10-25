package com.kalo.auth.security;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverAccountAccessPolicyTest {
    private final DriverRepository drivers=mock(DriverRepository.class);
    private final DriverAccountAccessPolicy policy=new DriverAccountAccessPolicy(drivers);
    private User driver() { var user=new User();user.setId(10L);user.setPhone("+355690000001");
        user.setRole(UserRole.DRIVER);user.setStatus(UserStatus.ACTIVE);user.setPhoneVerified(true);return user; }
    @Test void otherRolesKeepTheirExistingAuthenticationRulesWithoutBindingQuery() {
        for(UserRole role:new UserRole[]{UserRole.CUSTOMER,UserRole.PARTNER,UserRole.ADMIN}) {
            var user=driver();user.setRole(role);user.setStatus(UserStatus.PENDING);
            assertThat(policy.canAuthenticate(user)).isTrue();
        }
        verifyNoInteractions(drivers);
    }
    @Test void missingPendingOrUnverifiedDriverIsRejectedBeforeQuery() {
        assertThat(policy.canAuthenticate(null)).isFalse();
        var user=driver();user.setId(null);assertThat(policy.canAuthenticate(user)).isFalse();
        user=driver();user.setStatus(UserStatus.PENDING);assertThat(policy.canAuthenticate(user)).isFalse();
        user=driver();user.setPhoneVerified(false);assertThat(policy.canAuthenticate(user)).isFalse();verifyNoInteractions(drivers);
    }
    @Test void activeVerifiedDriverStillRequiresCurrentDatabaseBinding() {
        var user=driver();assertThat(policy.canAuthenticate(user)).isFalse();
        verify(drivers).hasEligibleUserBinding(10L,user.getPhone(),UserRole.DRIVER,UserStatus.ACTIVE,
                DriverStatus.ACTIVE,CompanyStatus.ACTIVE,VerificationStatus.APPROVED);
    }
    @Test void onlyEligibleBindingEnablesDriver() {
        var user=driver();when(drivers.hasEligibleUserBinding(10L,user.getPhone(),UserRole.DRIVER,UserStatus.ACTIVE,
                DriverStatus.ACTIVE,CompanyStatus.ACTIVE,VerificationStatus.APPROVED)).thenReturn(true);
        assertThat(policy.canAuthenticate(user)).isTrue();
    }
}
