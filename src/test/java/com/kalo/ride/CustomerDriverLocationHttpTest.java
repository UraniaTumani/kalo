package com.kalo.ride;

import com.kalo.common.exception.GlobalExceptionHandler;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.ride.controller.RideSearchController;
import com.kalo.ride.service.RideSearchService;
import com.kalo.ride.service.RideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CustomerDriverLocationHttpTest {

    private RideService rideService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        rideService = mock(RideService.class);
        RideSearchService rideSearchService = mock(RideSearchService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RideSearchController(rideSearchService, rideService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void endpointReturnsFreshGpsResponse() throws Exception {
        Instant updatedAt = Instant.parse("2026-10-21T19:00:00Z");
        when(rideService.getDriverLocation(21L)).thenReturn(
                new DriverLocationResponse(17L, 41.3275, 19.8187, updatedAt));

        mockMvc.perform(get("/api/v1/rides/21/driver-location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(17))
                .andExpect(jsonPath("$.latitude").value(41.3275))
                .andExpect(jsonPath("$.longitude").value(19.8187))
                .andExpect(jsonPath("$.locationUpdatedAt").value(updatedAt.toString()));

        verify(rideService).getDriverLocation(21L);
    }

    @Test
    void endpointReturnsNotFoundWhenServiceRefusesLocation() throws Exception {
        when(rideService.getDriverLocation(21L))
                .thenThrow(new ResourceNotFoundException("Driver location not available"));

        mockMvc.perform(get("/api/v1/rides/21/driver-location"))
                .andExpect(status().isNotFound());

        verify(rideService).getDriverLocation(21L);
    }
}
