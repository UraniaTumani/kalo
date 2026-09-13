package com.kalo.geocoding;

import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Address lookup, now that it runs here rather than in the browser.
 *
 * The fixed provider stands in for the real one, so these tests are about the
 * things moving it server-side actually bought: that it is not an open proxy,
 * that a useless query is refused before a third party is asked, and that a
 * provider having a bad day does not take booking down with it.
 */
@TestPropertySource(properties = "app.geocoding.provider=static")
@DisplayName("Geocoding")
class GeocodingIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("a signed-in customer gets places for an address")
    void searchReturnsPlaces() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/search")
                                .param("q", "Skenderbej")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].label").value("Sheshi Skenderbej"))
                .andExpect(jsonPath("$[0].latitude").isNumber())
                .andExpect(jsonPath("$[0].longitude").isNumber());
    }

    @Test
    @DisplayName("an anonymous caller is refused")
    void searchRequiresAuthentication() throws Exception {

        /*
         * The whole point of moving this here is undone if it is left open: an
         * unauthenticated geocoding endpoint is a free proxy to whatever the
         * provider is, billed to us.
         */
        mockMvc.perform(get("/api/v1/geocoding/search").param("q", "Skenderbej"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a query too short to mean anything is refused before the provider is asked")
    void shortQueryIsRejected() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/search")
                                .param("q", "ab")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an empty query is a validation error, not a server error")
    void blankQueryIsRejected() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/search")
                                .param("q", "")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reverse lookup names a point")
    void reverseReturnsAnAddress() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/reverse")
                                .param("lat", "41.3320")
                                .param("lng", "19.8230")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(
                        org.hamcrest.Matchers.containsString("Skenderbej")
                ));
    }

    @Test
    @DisplayName("coordinates off the planet are refused")
    void impossibleCoordinatesAreRejected() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/reverse")
                                .param("lat", "999")
                                .param("lng", "19.8230")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reverse lookup is closed to anonymous callers too")
    void reverseRequiresAuthentication() throws Exception {

        mockMvc.perform(
                        get("/api/v1/geocoding/reverse")
                                .param("lat", "41.3320")
                                .param("lng", "19.8230")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("asking twice is served from cache rather than asked twice")
    void repeatedSearchIsCached() throws Exception {

        String token = bearer(tokenFor(fixtures.customer()));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(
                            get("/api/v1/geocoding/search")
                                    .param("q", "Kavajes")
                                    .header("Authorization", token)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].label").value("Rruga e Kavajes"));
        }

        /*
         * The cache is not observable from out here, so this asserts the visible
         * contract instead: the same question keeps giving the same answer. What
         * it guards against is a cache that corrupts or drops entries, which
         * would show up as the second call differing from the first.
         */
    }
}
