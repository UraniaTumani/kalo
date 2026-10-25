package com.kalo.ride;

import com.kalo.auth.security.CustomUserDetailsService;
import com.kalo.auth.security.JwtAuthenticationFilter;
import com.kalo.auth.security.JwtService;
import com.kalo.common.exception.ErrorResponseWriter;
import com.kalo.config.JwtAuthenticationEntryPoint;
import com.kalo.config.RestAccessDeniedHandler;
import com.kalo.config.SecurityConfig;
import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.ride.controller.RideSearchController;
import com.kalo.ride.service.RideSearchService;
import com.kalo.ride.service.RideService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the application's real SecurityFilterChain with a mocked ride service.
 * No database or Testcontainers are started.
 */
@WebMvcTest(RideSearchController.class)
// Do not load KaloBackendApplication: @EnableJpaAuditing needs a JPA metamodel.
// This MVC slice intentionally has no persistence configuration or database.
@ContextConfiguration(classes = {
        RideSearchController.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ErrorResponseWriter.class
})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ErrorResponseWriter.class
})
class CustomerDriverLocationSecurityTest {
    private static final String URL = "/api/v1/rides/21/driver-location";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RideService rideService;
    @MockitoBean private RideSearchService rideSearchService;
    @MockitoBean private CustomUserDetailsService userDetailsService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rideService);
    }

    @Test
    void partnerCannotReadCustomerDriverGps() throws Exception {
        mockMvc.perform(get(URL).with(user("partner").roles("PARTNER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rideService);
    }

    @Test
    void adminCannotReadCustomerDriverGps() throws Exception {
        mockMvc.perform(get(URL).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rideService);
    }

    @Test
    void customerMayReachOwnershipCheckedService() throws Exception {
        when(rideService.getDriverLocation(21L)).thenReturn(
                new DriverLocationResponse(17L, 41.3275, 19.8187,
                        Instant.parse("2026-10-22T12:00:00Z")));

        mockMvc.perform(get(URL).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(17));

        verify(rideService).getDriverLocation(21L);
    }
}
