package com.kalo.auth.security;

import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceDriverTest {

    private final UserRepository users = mock(UserRepository.class);
    private final DriverAccountAccessPolicy access = mock(DriverAccountAccessPolicy.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(users,access);

    @Test
    void pendingDriverCannotAuthenticateBeforeActivation() {
        UserDetails details = details(UserRole.DRIVER, UserStatus.PENDING);
        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void activeDriverCanAuthenticateAndGetsOnlyDriverRole() {
        UserDetails details = details(UserRole.DRIVER, UserStatus.ACTIVE);
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_DRIVER");
    }

    @Test
    void pendingPartnerStillCanAuthenticateToCompleteOnboarding() {
        assertThat(details(UserRole.PARTNER, UserStatus.PENDING).isEnabled()).isTrue();
    }

    @Test
    void suspendedDriverIsLocked() {
        UserDetails details = details(UserRole.DRIVER, UserStatus.SUSPENDED);
        assertThat(details.isAccountNonLocked()).isFalse();
        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void activeDriverWithoutEligibleBindingIsDisabled() {
        User user=new User();user.setPhone("+355690000001");user.setPasswordHash("hash");
        user.setRole(UserRole.DRIVER);user.setStatus(UserStatus.ACTIVE);
        when(users.findByPhone(user.getPhone())).thenReturn(Optional.of(user));
        when(access.canAuthenticate(user)).thenReturn(false);
        assertThat(service.loadUserByUsername(user.getPhone()).isEnabled()).isFalse();
    }

    private UserDetails details(UserRole role, UserStatus status) {
        User user = new User();
        user.setPhone("+355691110001");
        user.setPasswordHash("irrelevant-to-details");
        user.setRole(role);
        user.setStatus(status);
        when(users.findByPhone(user.getPhone())).thenReturn(Optional.of(user));
        when(access.canAuthenticate(user)).thenReturn(role != UserRole.DRIVER || status == UserStatus.ACTIVE);
        return service.loadUserByUsername(user.getPhone());
    }
}
