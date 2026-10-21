package com.kalo.ride;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.location.entity.DriverLocation;
import com.kalo.location.repository.DriverLocationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.service.RideServiceImpl;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerDriverLocationTest {

    @Mock private RideRepository rideRepository;
    @Mock private DriverLocationRepository driverLocationRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private RideServiceImpl service;

    @BeforeEach
    void authenticatedCustomer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("355600000001", "unused"));
        User user = new User();
        user.setId(4L);
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByPhone("355600000001")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Ride activeRide() {
        Driver driver = new Driver();
        driver.setId(17L);
        TaxiCompany company = new TaxiCompany();
        company.setId(8L);

        Ride ride = new Ride();
        ride.setDriver(driver);
        ride.setCompany(company);
        ride.setStatus(RideStatus.DRIVER_ASSIGNED);
        return ride;
    }

    @Test
    void returnsFreshPositionForOwnedActiveRide() {
        Ride ride = activeRide();
        when(rideRepository.findByIdAndCustomerId(21L, 4L)).thenReturn(Optional.of(ride));
        DriverLocation location = new DriverLocation();
        location.setLatitude(41.3275);
        location.setLongitude(19.8187);
        location.setLocationUpdatedAt(Instant.now().minusSeconds(15));
        when(driverLocationRepository.findByDriverIdAndDriverCompanyId(17L, 8L))
                .thenReturn(Optional.of(location));

        var response = service.getDriverLocation(21L);

        assertThat(response.driverId()).isEqualTo(17L);
        assertThat(response.latitude()).isEqualTo(41.3275);
        assertThat(response.longitude()).isEqualTo(19.8187);
        assertThat(response.locationUpdatedAt()).isEqualTo(location.getLocationUpdatedAt());
    }

    @Test
    void anotherCustomersRideCannotExposeGps() {
        when(rideRepository.findByIdAndCustomerId(21L, 4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDriverLocation(21L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(driverLocationRepository);
    }

    @Test
    void completedRideCannotExposeGps() {
        Ride ride = activeRide();
        ride.setStatus(RideStatus.COMPLETED);
        when(rideRepository.findByIdAndCustomerId(21L, 4L)).thenReturn(Optional.of(ride));
        assertThatThrownBy(() -> service.getDriverLocation(21L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(driverLocationRepository);
    }

    @Test
    void unassignedRideCannotExposeGps() {
        Ride ride = activeRide();
        ride.setDriver(null);
        ride.setStatus(RideStatus.REQUESTED);
        when(rideRepository.findByIdAndCustomerId(21L, 4L)).thenReturn(Optional.of(ride));
        assertThatThrownBy(() -> service.getDriverLocation(21L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(driverLocationRepository);
    }

    @Test
    void stalePositionCannotExposeGps() {
        when(rideRepository.findByIdAndCustomerId(21L, 4L))
                .thenReturn(Optional.of(activeRide()));
        DriverLocation location = new DriverLocation();
        location.setLocationUpdatedAt(Instant.now().minusSeconds(180));
        when(driverLocationRepository.findByDriverIdAndDriverCompanyId(17L, 8L))
                .thenReturn(Optional.of(location));
        assertThatThrownBy(() -> service.getDriverLocation(21L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingPositionCannotExposeGps() {
        when(rideRepository.findByIdAndCustomerId(21L, 4L))
                .thenReturn(Optional.of(activeRide()));
        when(driverLocationRepository.findByDriverIdAndDriverCompanyId(17L, 8L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDriverLocation(21L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
