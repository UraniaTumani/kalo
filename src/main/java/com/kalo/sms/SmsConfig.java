package com.kalo.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Arrays;
import java.util.Locale;

/**
 * Chooses the SMS provider from configuration, the same way
 * {@code GeocodingConfig} chooses a geocoder.
 *
 * There is one provider today and it sends nothing. Adding a real one is a new
 * class implementing {@link SmsSender} and one more branch below — no
 * credentials are invented here, and no provider's SDK is on the classpath, so
 * this seam costs nothing until somebody has an account to put behind it.
 *
 * An unrecognised value fails at startup rather than falling back to the fake.
 * A typo in {@code app.sms.provider} must not be the reason a deployment
 * silently stops delivering codes.
 */
@Slf4j
@Configuration
@EnableAsync
public class SmsConfig {

    @Bean
    public SmsSender smsSender(
            Environment environment,
            @Value("${app.sms.provider:log}") String provider,
            @Value("${app.sms.log-message:false}") boolean logMessage
    ) {

        boolean development =
                Arrays.asList(environment.getActiveProfiles()).contains("dev")
                        || Arrays.asList(environment.getActiveProfiles()).contains("test");

        if ("log".equalsIgnoreCase(provider)) {

            if (!development) {
                log.warn(
                        "No SMS provider is configured (app.sms.provider=log). Password "
                                + "recovery codes will not be delivered and recovery depends "
                                + "on the admin fallback. Set app.sms.provider once an account "
                                + "with a provider exists."
                );
            }

            /*
             * logMessage is gated on the profile as well as the property, so
             * setting app.sms.log-message=true against a real deployment does
             * not start writing live codes into its logs.
             */
            return new FakeSmsSender(logMessage && development, development);
        }

        throw new IllegalStateException(
                "Unknown app.sms.provider '" + provider + "'. Only 'log' is implemented. "
                        + "A real provider is a new class implementing SmsSender and one more "
                        + "branch in SmsConfig; nothing else in the application changes."
        );
    }

    /**
     * The thread the SMS goes out on.
     *
     * Separate from the request thread for a security reason rather than a
     * throughput one. A provider's HTTP call takes a few hundred milliseconds
     * for a number that exists and zero for a number that does not, which is
     * exactly the difference the constant-time floor on
     * {@code POST /password/forgot} exists to hide — a synchronous send would
     * walk straight through a 250ms floor and reopen the enumeration channel
     * wider than it was before the floor was added.
     *
     * Synchronous when {@code app.sms.async} is false, which the test profile
     * sets: a test that has to poll for a background send is a flaky test, and
     * the property under test there is the message, not the threading.
     */
    @Bean(name = "smsExecutor")
    public TaskExecutor smsExecutor(@Value("${app.sms.async:true}") boolean async) {

        if (!async) {
            return new SyncTaskExecutor();
        }

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sms-");

        /*
         * Bounded. Without a cap, a burst of recovery requests would start a
         * thread per message; with one, the overflow waits. The queue this
         * protects is a provider's rate limit as much as the JVM's memory.
         */
        executor.setConcurrencyLimit(4);

        return executor;
    }
}
