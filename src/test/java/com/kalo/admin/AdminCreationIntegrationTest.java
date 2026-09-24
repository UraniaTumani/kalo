package com.kalo.admin;

import com.kalo.admin.bootstrap.AdminBootstrap;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Getting the first administrator, and then the rest.
 *
 * Every administrative route is behind {@code hasRole("ADMIN")}, so the first
 * one cannot come through the API — it came from a hand-written UPDATE against
 * production, which is not a procedure, and it silently blocked password
 * recovery, whose entire identity check is an administrator on the telephone.
 *
 * The tests that matter here are the refusals. A bootstrap that promoted an
 * existing account, or quietly overwrote somebody's password, or ran a second
 * time, would each be a way to take over the application from a configuration
 * value — so each one is pinned.
 */
class AdminCreationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOTSTRAP_PHONE = "+355691111111";
    private static final String BOOTSTRAP_PASSWORD = "FirstAdmin123!";

    @Autowired
    AdminBootstrap adminBootstrap;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    /* ------------------------------------------------------- bootstrap */

    @Test
    @DisplayName("the first administrator is created from configuration")
    void bootstrapCreatesTheFirstAdministrator() {

        configure(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);

        adminBootstrap.createFirstAdministratorIfMissing();

        User admin = userRepository.findByPhone(BOOTSTRAP_PHONE).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);

        /* Hashed, not stored. */
        assertThat(admin.getPasswordHash()).doesNotContain(BOOTSTRAP_PASSWORD);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName("the created administrator can actually sign in")
    void bootstrappedAdministratorCanSignIn() throws Exception {

        configure(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);
        adminBootstrap.createFirstAdministratorIfMissing();

        String token = login(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);

        /* And is genuinely an administrator, not merely authenticated. */
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("Authorization", bearer(token))
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("it does nothing once an administrator exists")
    void bootstrapIsSkippedWhenAnAdministratorExists() {

        User existing = fixtures.admin();

        configure(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);
        adminBootstrap.createFirstAdministratorIfMissing();

        assertThat(userRepository.findByPhone(BOOTSTRAP_PHONE)).isEmpty();

        /*
         * By id, not by instance: User has no equals, so a row read back in a
         * later transaction is a different object from the fixture's.
         */
        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.ADMIN)
                .extracting(User::getId)
                .containsExactly(existing.getId());
    }

    @Test
    @DisplayName("running twice does not produce a second administrator")
    void bootstrapIsIdempotent() {

        configure(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);

        adminBootstrap.createFirstAdministratorIfMissing();
        adminBootstrap.createFirstAdministratorIfMissing();

        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.ADMIN)
                .hasSize(1);
    }

    @Test
    @DisplayName("it refuses a phone that already belongs to somebody")
    void bootstrapRefusesToPromoteAnExistingAccount() {

        User customer = fixtures.customer();
        String hashBefore = customer.getPasswordHash();

        configure(customer.getPhone(), BOOTSTRAP_PASSWORD);
        adminBootstrap.createFirstAdministratorIfMissing();

        User after = userRepository.findById(customer.getId()).orElseThrow();

        /*
         * The whole point. A configuration value must not be able to turn
         * somebody's account into an administrator, and must never overwrite
         * the password on an account it did not create.
         */
        assertThat(after.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("it refuses a password shorter than the registration minimum")
    void bootstrapRefusesAShortPassword() {

        configure(BOOTSTRAP_PHONE, "short");
        adminBootstrap.createFirstAdministratorIfMissing();

        assertThat(userRepository.findByPhone(BOOTSTRAP_PHONE)).isEmpty();
    }

    @Test
    @DisplayName("it does nothing when only half configured")
    void bootstrapRefusesHalfConfiguration() {

        configure(BOOTSTRAP_PHONE, "");
        adminBootstrap.createFirstAdministratorIfMissing();
        assertThat(userRepository.findByPhone(BOOTSTRAP_PHONE)).isEmpty();

        configure("", BOOTSTRAP_PASSWORD);
        adminBootstrap.createFirstAdministratorIfMissing();
        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.ADMIN)
                .isEmpty();
    }

    @Test
    @DisplayName("unconfigured, it creates nothing")
    void bootstrapDoesNothingWhenUnconfigured() {

        configure("", "");
        adminBootstrap.createFirstAdministratorIfMissing();

        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.ADMIN)
                .isEmpty();
    }

    @Test
    @DisplayName("a phone typed loosely still lands normalised")
    void bootstrapNormalisesThePhone() {

        configure("069 111 1111", BOOTSTRAP_PASSWORD);
        adminBootstrap.createFirstAdministratorIfMissing();

        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.ADMIN)
                .singleElement()
                .satisfies(admin -> assertThat(admin.getPhone()).startsWith("+355"));
    }

    /* -------------------------------------------------------- endpoint */

    @Test
    @DisplayName("an administrator can create another one")
    void administratorCreatesAnotherAdministrator() throws Exception {

        User admin = fixtures.admin();

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Second",
                                          "lastName": "Administrator",
                                          "phone": "+355692222222",
                                          "password": "SecondAdmin123!"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        /* And the new one works, which is the only proof that matters. */
        String token = login("+355692222222", "SecondAdmin123!");

        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("Authorization", bearer(token))
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("nobody else can create an administrator")
    void onlyAdministratorsMayCreateAdministrators() throws Exception {

        User customer = fixtures.customer();
        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);

        String body = """
                {
                  "firstName": "Sneaky",
                  "lastName": "Administrator",
                  "phone": "+355693333333",
                  "password": "Sneaky123456!"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(customer)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(partner)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByPhone("+355693333333")).isEmpty();
    }

    @Test
    @DisplayName("a phone already in use is refused rather than taken over")
    void duplicatePhoneIsRefused() throws Exception {

        User admin = fixtures.admin();
        User customer = fixtures.customer();
        String hashBefore = customer.getPasswordHash();

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Taken",
                                          "lastName": "Administrator",
                                          "phone": "%s",
                                          "password": "TakenAdmin123!"
                                        }
                                        """.formatted(customer.getPhone()))
                )
                .andExpect(status().isConflict());

        User after = userRepository.findById(customer.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("a weak password is refused")
    void weakPasswordIsRefused() throws Exception {

        User admin = fixtures.admin();

        mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Weak",
                                          "lastName": "Administrator",
                                          "phone": "+355694444444",
                                          "password": "short"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByPhone("+355694444444")).isEmpty();
    }

    @Test
    @DisplayName("the response never carries the password back")
    void responseDoesNotEchoThePassword() throws Exception {

        User admin = fixtures.admin();

        String body = mockMvc.perform(
                        post("/api/v1/admin/users/admins")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Quiet",
                                          "lastName": "Administrator",
                                          "phone": "+355695555555",
                                          "password": "QuietAdmin123!"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("QuietAdmin123!");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("$2");
    }

    /* --------------------------------------------------------- helpers */

    /**
     * Sets the bootstrap properties directly.
     *
     * The component reads them with {@code @Value}, and the alternative —
     * a @TestPropertySource per case — would need one context per scenario,
     * for something these tests change on every one of them.
     */
    private void configure(String phone, String password) {
        ReflectionTestUtils.setField(adminBootstrap, "phone", phone);
        ReflectionTestUtils.setField(adminBootstrap, "password", password);
        ReflectionTestUtils.setField(adminBootstrap, "firstName", "KALO");
        ReflectionTestUtils.setField(adminBootstrap, "lastName", "Administrator");
    }
}
