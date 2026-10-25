package com.kalo.driver.controller;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.service.DriverInvitationRedemption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;

/** The bearer invitation selects the account; callers cannot select a phone or role. */
@RestController
@ConditionalOnProperty(name="app.driver-invitation.activation-api-enabled", havingValue="true")
public class DriverActivationController {
    private final DriverInvitationRedemption activation;

    public DriverActivationController(DriverInvitationRedemption activation,
            @Value("${app.driver-invitation.activation-enabled:false}") boolean enabled) {
        if (!enabled) throw new IllegalStateException("Driver activation API requires activation-enabled");
        this.activation=activation;
    }

    public record Request(String token, String password) {
        @Override public String toString() { return "DriverActivationRequest[REDACTED]"; }
    }

    @PostMapping("/api/v1/auth/driver/activate")
    public ResponseEntity<Void> activate(@RequestBody Request request) {
        // Validate without echoing either credential or delegating invalid passwords to bcrypt.
        if (request==null || request.token()==null || request.token().length()!=43
                || request.password()==null || request.password().isBlank()
                || request.password().length()<8 || request.password().length()>72
                || request.password().getBytes(StandardCharsets.UTF_8).length>72) {
            throw new InvalidOperationException("Invalid driver invitation or password");
        }
        activation.activate(request.token(),request.password());
        // Login remains a separate call; never expose IDs, tokens, or password hashes here.
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
