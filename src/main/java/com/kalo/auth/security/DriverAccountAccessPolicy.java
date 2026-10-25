package com.kalo.auth.security;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
/** Driver-only gate shared by login, per-request JWT checks and refresh. */
@Component
@RequiredArgsConstructor
public class DriverAccountAccessPolicy {
    private final DriverRepository drivers;
    public boolean canAuthenticate(User user) {
        if (user == null) return false;
        if (user.getRole() != UserRole.DRIVER) return true;
        return user.getId() != null && user.getStatus() == UserStatus.ACTIVE && user.isPhoneVerified()
                && drivers.hasEligibleUserBinding(user.getId(),user.getPhone(),UserRole.DRIVER,UserStatus.ACTIVE,
                    DriverStatus.ACTIVE,CompanyStatus.ACTIVE,VerificationStatus.APPROVED);
    }
}
