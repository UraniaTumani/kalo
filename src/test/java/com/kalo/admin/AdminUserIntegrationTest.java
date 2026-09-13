package com.kalo.admin;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin control over user accounts.
 *
 * Suspension is the only lever the platform has over someone who is misusing
 * it, so the guard rails around it matter as much as the action: an admin who
 * locks themselves out cannot undo anything, including the lockout.
 */
@DisplayName("Admin user administration")
class AdminUserIntegrationTest extends AbstractIntegrationTest {

    @Nested
    @DisplayName("Listing")
    class Listing {

        @Test
        @DisplayName("comes back as a page")
        void isPaged() throws Exception {

            User admin = fixtures.admin();
            fixtures.customer();
            fixtures.customer();

            mockMvc.perform(
                            get("/api/v1/admin/users")
                                    .param("size", "2")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("filters by role")
        void filtersByRole() throws Exception {

            User admin = fixtures.admin();
            fixtures.customer();

            mockMvc.perform(
                            get("/api/v1/admin/users")
                                    .param("role", "ADMIN")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].role").value("ADMIN"));
        }

        @Test
        @DisplayName("filters by status")
        void filtersByStatus() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            suspend(admin, target);

            mockMvc.perform(
                            get("/api/v1/admin/users")
                                    .param("status", "SUSPENDED")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].userId").value(target.getId()));
        }

        @Test
        @DisplayName("never exposes a password hash")
        void hidesCredentials() throws Exception {

            User admin = fixtures.admin();

            mockMvc.perform(
                            get("/api/v1/admin/users")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        }
    }

    @Nested
    @DisplayName("One user")
    class OneUser {

        @Test
        @DisplayName("is readable by id")
        void readsById() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            mockMvc.perform(
                            get("/api/v1/admin/users/" + target.getId())
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(target.getId()))
                    .andExpect(jsonPath("$.role").value("CUSTOMER"));
        }

        @Test
        @DisplayName("a user who does not exist is a 404, not a 500")
        void unknownUserIsNotFound() throws Exception {

            User admin = fixtures.admin();

            mockMvc.perform(
                            get("/api/v1/admin/users/999999")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Suspending")
    class Suspending {

        @Test
        @DisplayName("moves the account to SUSPENDED")
        void suspends() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            mockMvc.perform(
                            post("/api/v1/admin/users/" + target.getId() + "/suspend")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));
        }

        @Test
        @DisplayName("a suspended user can no longer sign in")
        void suspendedUserCannotSignIn() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            suspend(admin, target);

            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"phone":"%s","password":"%s"}
                                            """.formatted(
                                            target.getPhone(), TestDataFactory.PASSWORD
                                    ))
                    )
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an admin cannot suspend themselves")
        void cannotSuspendSelf() throws Exception {

            User admin = fixtures.admin();

            /*
             * The one action with no way back: a suspended admin cannot sign in to
             * reactivate anybody, including themselves.
             */
            mockMvc.perform(
                            post("/api/v1/admin/users/" + admin.getId() + "/suspend")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("suspending twice is refused rather than silently repeated")
        void cannotSuspendTwice() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            suspend(admin, target);

            mockMvc.perform(
                            post("/api/v1/admin/users/" + target.getId() + "/suspend")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Reactivating")
    class Reactivating {

        @Test
        @DisplayName("puts the account back and lets them sign in again")
        void reactivates() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            suspend(admin, target);

            mockMvc.perform(
                            post("/api/v1/admin/users/" + target.getId() + "/reactivate")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // Suspension has to be genuinely reversible, not just cosmetically.
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"phone":"%s","password":"%s"}
                                            """.formatted(
                                            target.getPhone(), TestDataFactory.PASSWORD
                                    ))
                    )
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("reactivating an active account is refused")
        void cannotReactivateActiveUser() throws Exception {

            User admin = fixtures.admin();
            User target = fixtures.customer();

            mockMvc.perform(
                            post("/api/v1/admin/users/" + target.getId() + "/reactivate")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Access")
    class Access {

        @Test
        @DisplayName("a customer cannot administer users")
        void customerIsRefused() throws Exception {

            User customer = fixtures.customer();
            User target = fixtures.customer();

            mockMvc.perform(
                            post("/api/v1/admin/users/" + target.getId() + "/suspend")
                                    .header("Authorization", bearer(tokenFor(customer)))
                    )
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a partner cannot administer users")
        void partnerIsRefused() throws Exception {

            User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);

            mockMvc.perform(
                            get("/api/v1/admin/users")
                                    .header("Authorization", bearer(tokenFor(partner)))
                    )
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller gets nothing")
        void anonymousIsRefused() throws Exception {

            mockMvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isUnauthorized());
        }
    }

    /* ----------------------------------------------------------- helpers */

    private void suspend(User admin, User target) throws Exception {

        mockMvc.perform(
                        post("/api/v1/admin/users/" + target.getId() + "/suspend")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk());
    }
}
