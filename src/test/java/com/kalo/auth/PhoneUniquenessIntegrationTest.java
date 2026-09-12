package com.kalo.auth;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The point of normalising: one person cannot hold two accounts by typing their
 * number differently the second time.
 */
@DisplayName("Phone uniqueness across spellings")
class PhoneUniquenessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository userRepository;

    private static final String CANONICAL = "+355699887766";

    private void register(String phone, int expectedStatus) throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/register/customer")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName":"Test","lastName":"Rider",
                                          "phone":"%s","password":"%s"
                                        }
                                        """.formatted(phone, TestDataFactory.PASSWORD))
                )
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("registration stores the number in +355 form")
    void registrationStoresCanonicalForm() throws Exception {

        register("069 988 7766", 201);

        assertThat(userRepository.findByPhone("+355699887766")).isPresent();
    }

    @Test
    @DisplayName("the same number spelled differently is refused, not duplicated")
    void duplicateSpellingIsRejected() throws Exception {

        register(CANONICAL, 201);

        // Every one of these is the same subscriber.
        for (String spelling : new String[]{
                CANONICAL,
                "+355 69 988 7766",
                "355699887766",
                "00355699887766",
                "0699887766",
                "699887766"
        }) {
            mockMvc.perform(
                            post("/api/v1/auth/register/customer")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "firstName":"Impostor","lastName":"Rider",
                                              "phone":"%s","password":"%s"
                                            }
                                            """.formatted(spelling, TestDataFactory.PASSWORD))
                    )
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").exists());
        }

        assertThat(userRepository.count())
                .as("no second account was created for any spelling")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a user can sign in with a different spelling than they registered with")
    void loginAcceptsAnySpelling() throws Exception {

        register(CANONICAL, 201);

        // Registered in +355 form, signing in with the national form.
        String token = login("0699887766", TestDataFactory.PASSWORD);

        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("a driver phone already used by the same company is refused")
    void duplicateDriverPhoneIsRejected() throws Exception {

        var taxi = fixtures.bookableCompany();
        String partnerToken = tokenFor(taxi.company().getOwner());

        String body = """
                {
                  "firstName":"Ilir","lastName":"Balla",
                  "phone":"%s",
                  "licenseNumber":"DRV-UNIQUE-1",
                  "licenseExpiryDate":"2030-01-01"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/partner/drivers")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content(body.formatted("069 111 2233"))
                )
                .andExpect(status().is2xxSuccessful());

        // Same number, different spelling, and a different licence so the
        // licence rule cannot be what rejects it.
        mockMvc.perform(
                        post("/api/v1/partner/drivers")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName":"Other","lastName":"Driver",
                                          "phone":"+355691112233",
                                          "licenseNumber":"DRV-UNIQUE-2",
                                          "licenseExpiryDate":"2030-01-01"
                                        }
                                        """)
                )
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a number with separators is accepted rather than rejected as malformed")
    void separatorsAreAccepted() throws Exception {
        register("+355 (69) 988-7755", 201);
    }
}
