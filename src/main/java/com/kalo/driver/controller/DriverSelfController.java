package com.kalo.driver.controller;

import com.kalo.driver.dto.DriverSelfResponse;
import com.kalo.driver.service.DriverSelfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DriverSelfController {
    private final DriverSelfService self;

    @GetMapping("/api/v1/driver/me")
    public ResponseEntity<DriverSelfResponse> current() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(self.current());
    }
}
