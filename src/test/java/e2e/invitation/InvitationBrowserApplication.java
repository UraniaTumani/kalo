package e2e.invitation;

import com.kalo.KaloBackendApplication;
import com.kalo.driver.service.DriverInvitationDeliveryCipher;
import com.kalo.driver.service.DriverInvitationTransport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-classpath launcher only; absent from the normal application jar. */
public class InvitationBrowserApplication {
    public static void main(String[] args) {
        // Refuse to run this credential-capturing fixture against any other stack.
        if (!"jdbc:postgresql://postgres:5432/kalo_invitation_e2e".equals(System.getenv("DB_URL"))
                || !"dev,invitation-e2e".equals(System.getenv("SPRING_PROFILES_ACTIVE"))) {
            throw new IllegalStateException("Invitation browser fixture requires its dedicated Compose stack");
        }
        SpringApplication.run(new Class<?>[]{KaloBackendApplication.class, Fixtures.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Fixtures {
        @Bean Mailbox invitationBrowserMailbox() { return new Mailbox(); }
        @Bean DriverInvitationDeliveryCipher invitationBrowserCipher() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            try { return new DriverInvitationDeliveryCipher(key); }
            finally { java.util.Arrays.fill(key, (byte) 0); }
        }
        @Bean DriverInvitationTransport invitationBrowserTransport(Mailbox mailbox) {
            return (destination, token, requestKey) -> {
                mailbox.tokens.put(destination, token);
                return DriverInvitationTransport.Outcome.ACCEPTED;
            };
        }
    }

    // Outside com.kalo's component scan; registered only by the explicit test launcher.
    // No production controller, provider adapter, or security override is introduced.
    @RestController
    public static class Mailbox {
        private final Map<String, String> tokens = new ConcurrentHashMap<>();
        @GetMapping("/api/v1/auth/e2e-invitation-mailbox")
        public ResponseEntity<Map<String, String>> take(@RequestParam("destination") String destination) {
            String token = tokens.remove(destination);
            if (token == null) return ResponseEntity.noContent().header("Cache-Control", "no-store").header("X-Kalo-Invitation-Fixture", "isolated-compose").build();
            return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Kalo-Invitation-Fixture", "isolated-compose").body(Map.of("token", token));
        }
    }
}
