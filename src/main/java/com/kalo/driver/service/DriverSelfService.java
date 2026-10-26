package com.kalo.driver.service;

import com.kalo.auth.security.DriverAccountAccessPolicy;
import com.kalo.driver.dto.DriverSelfResponse;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DriverSelfService {
    private final DriverRepository drivers;
    private final DriverAccountAccessPolicy access;

    @Transactional(readOnly = true)
    public DriverSelfResponse current() {
        var actor = SecurityContextHolder.getContext().getAuthentication();
        if (actor == null || !actor.isAuthenticated() || actor.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_DRIVER".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("Driver authentication required");
        }
        var driver = drivers.findSelfByUserPhone(actor.getName())
                .orElseThrow(() -> new AccessDeniedException("Driver account unavailable"));
        var user = driver.getUser();
        if (user.getRole() != UserRole.DRIVER || !access.canAuthenticate(user)) {
            throw new AccessDeniedException("Driver account unavailable");
        }
        return new DriverSelfResponse(driver.getId(), driver.getCompany().getId(),
                driver.getFirstName(), driver.getLastName(), driver.getPhone(),
                driver.getStatus(), driver.getAvailabilityStatus());
    }
}
