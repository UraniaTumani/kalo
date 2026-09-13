package com.kalo.auth.service;

import com.kalo.auth.dto.CustomerRegisterRequest;
import com.kalo.auth.dto.UserResponse;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.util.PhoneNumberNormalizer;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kalo.auth.dto.LoginRequest;
import com.kalo.auth.dto.LoginResponse;
import com.kalo.auth.dto.RefreshRequest;
import com.kalo.auth.security.JwtService;
import com.kalo.common.exception.UnauthorizedException;
import com.kalo.auth.dto.PartnerRegisterRequest;
import com.kalo.auth.dto.PartnerRegisterResponse;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import com.kalo.auth.security.CustomUserDetailsService;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final RefreshTokenService refreshTokenService;


    @Override
    @Transactional
    public UserResponse registerCustomer(CustomerRegisterRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());

        String email = request.email() == null
                ? null
                : request.email().trim().toLowerCase();

        if (userRepository.existsByPhone(phone)) {
            throw new ConflictException(
                    "Phone number is already registered"
            );
        }

        if (email != null
                && !email.isBlank()
                && userRepository.existsByEmail(email)) {

            throw new ConflictException(
                    "Email is already registered"
            );
        }

        User user = new User();

        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPhone(phone);
        user.setEmail(
                email == null || email.isBlank()
                        ? null
                        : email
        );

        user.setPasswordHash(
                passwordEncoder.encode(request.password())
        );

        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setPhoneVerified(false);

        User savedUser = userRepository.save(user);

        return new UserResponse(
                savedUser.getId(),
                savedUser.getFirstName(),
                savedUser.getLastName(),
                savedUser.getPhone(),
                savedUser.getEmail(),
                savedUser.getRole(),
                savedUser.getStatus(),
                savedUser.isPhoneVerified()
        );
    }

    @Override
    @Transactional
    public PartnerRegisterResponse registerPartner(
            PartnerRegisterRequest request
    ) {

        String phone =
                PhoneNumberNormalizer.normalize(request.phone());

        String email =
                request.email() == null
                        ? null
                        : request.email()
                        .trim()
                        .toLowerCase();

        String nipt =
                request.nipt()
                        .trim()
                        .toUpperCase();

        if (userRepository.existsByPhone(phone)) {

            throw new ConflictException(
                    "Phone number is already registered"
            );
        }

        if (email != null
                && !email.isBlank()
                && userRepository.existsByEmail(email)) {

            throw new ConflictException(
                    "Email is already registered"
            );
        }

        if (taxiCompanyRepository.existsByNipt(nipt)) {

            throw new ConflictException(
                    "A taxi company with this NIPT is already registered"
            );
        }

        User user = new User();

        user.setFirstName(
                request.firstName().trim()
        );

        user.setLastName(
                request.lastName().trim()
        );

        user.setPhone(phone);

        user.setEmail(
                email == null || email.isBlank()
                        ? null
                        : email
        );

        user.setPasswordHash(
                passwordEncoder.encode(
                        request.password()
                )
        );

        user.setRole(
                UserRole.PARTNER
        );

        user.setStatus(
                UserStatus.PENDING
        );

        user.setPhoneVerified(false);

        User savedUser =
                userRepository.save(user);


        TaxiCompany company =
                new TaxiCompany();

        company.setOwner(savedUser);

        company.setLegalName(
                request.legalName().trim()
        );

        company.setDisplayName(
                request.displayName().trim()
        );

        company.setNipt(nipt);

        company.setPhone(phone);

        company.setEmail(
                email == null || email.isBlank()
                        ? null
                        : email
        );

        company.setAddress(
                request.address().trim()
        );

        company.setVerificationStatus(
                VerificationStatus.DRAFT
        );

        company.setStatus(
                CompanyStatus.INACTIVE
        );

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(company);


        return new PartnerRegisterResponse(
                savedUser.getId(),
                savedCompany.getId(),

                savedUser.getFirstName(),
                savedUser.getLastName(),
                savedUser.getPhone(),

                savedCompany.getLegalName(),
                savedCompany.getDisplayName(),
                savedCompany.getNipt(),

                savedUser.getStatus(),
                savedCompany.getVerificationStatus(),
                savedCompany.getStatus()
        );
    }

    @Override
    public LoginResponse login(LoginRequest request) {

        String phone = PhoneNumberNormalizer.normalize(request.phone());

        try {

            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            phone,
                            request.password()
                    )
            );

        } catch (AuthenticationException exception) {

            /*
             * Reason only, never the submitted credentials.
             */
            log.warn(
                    "Login failed for phone ending {}: {}",
                    maskPhone(phone),
                    exception.getClass().getSimpleName()
            );

            throw exception;
        }

        UserDetails userDetails =
                customUserDetailsService.loadUserByUsername(phone);

        User user = userRepository
                .findByPhone(phone)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        String token =
                jwtService.generateToken(userDetails);

        log.info(
                "Login succeeded for phone ending {}",
                maskPhone(phone)
        );

        return new LoginResponse(
                token,
                refreshTokenService.issue(user),
                "Bearer",
                jwtService.getExpirationSeconds()
        );
    }

    @Override
    @Transactional
    public LoginResponse refresh(RefreshRequest request) {

        return refreshTokenService
                .rotate(request.refreshToken())
                .map(rotation -> {

                    /*
                     * Reloaded rather than trusted: the account may have been
                     * suspended since the refresh token was issued, and this is
                     * the one place that would otherwise hand out a fresh hour
                     * of access to a locked-out user.
                     */
                    UserDetails userDetails =
                            customUserDetailsService.loadUserByUsername(
                                    rotation.user().getPhone()
                            );

                    if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {

                        refreshTokenService.revokeAllForUser(rotation.user().getId());

                        throw new UnauthorizedException(
                                "This account is no longer active"
                        );
                    }

                    return new LoginResponse(
                            jwtService.generateToken(userDetails),
                            rotation.refreshToken(),
                            "Bearer",
                            jwtService.getExpirationSeconds()
                    );
                })
                .orElseThrow(() -> new UnauthorizedException(
                        "Session expired, please sign in again"
                ));
    }

    @Override
    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    /**
     * Keeps only the last three digits, so logs stay useful for support
     * without recording a full personal phone number.
     */
    private String maskPhone(
            String phone
    ) {

        if (phone == null || phone.length() < 3) {
            return "***";
        }

        return "***" + phone.substring(phone.length() - 3);
    }
}