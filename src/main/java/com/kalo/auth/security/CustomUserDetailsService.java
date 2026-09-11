package com.kalo.auth.security;

import com.kalo.user.entity.User;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String phone)
            throws UsernameNotFoundException {

        User user = userRepository.findByPhone(phone)
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "User not found"
                        )
                );

        /*
         * PENDING is deliberately treated as authenticatable: a partner has to
         * log in while PENDING to finish onboarding and submit documents.
         * Ride-management operations are gated separately on an APPROVED +
         * ACTIVE taxi company.
         */
        boolean enabled =
                user.getStatus() != UserStatus.DISABLED;

        boolean accountNonLocked =
                user.getStatus() != UserStatus.SUSPENDED;

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getPhone())
                .password(user.getPasswordHash())
                .authorities(List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_" + user.getRole().name()
                        )
                ))
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .build();
    }
}
