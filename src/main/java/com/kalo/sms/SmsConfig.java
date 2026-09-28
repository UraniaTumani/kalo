package com.kalo.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;

/**
 * Chooses the SMS provider from configuration, the same way
 * {@code GeocodingConfig} chooses a geocoder.
 *
 * Two providers: {@code log}, which sends nothing and says so, and
 * {@code infobip}. Adding Twilio or a local Albanian aggregator is another
 * class implementing {@link SmsSender} and one more branch below — nothing
 * outside this package learns which company carries the message.
 *
 * No credentials are invented here and none has a default. A deployment that
 * asks for a real provider without supplying its account details fails at
 * startup, which is the only honest outcome: the alternative is an application
 * that starts, looks configured, and silently delivers nothing.
 *
 * An unrecognised value fails at startup too, rather than falling back to the
 * fake. A typo in {@code app.sms.provider} must not be the reason a deployment
 * stops delivering codes.
 */
@Slf4j
@Configuration
@EnableAsync
public class SmsConfig {

    /**
     * Short, but not as short as the geocoder's.
     *
     * A slow geocoder must not hold a request thread while somebody types the
     * next letter; this call already runs on its own executor, after the
     * response has gone out, so a few seconds cost nobody anything. Bounded all
     * the same, because the executor has four threads and a provider that hangs
     * would otherwise take all of them.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public SmsSender smsSender(
            Environment environment,
            @Value("${app.sms.provider:log}") String provider,
            @Value("${app.sms.log-message:false}") boolean logMessage,
            @Value("${app.sms.infobip.base-url:}") String infobipBaseUrl,
            @Value("${app.sms.infobip.api-key:}") String infobipApiKey,
            @Value("${app.sms.infobip.sender:}") String infobipSender
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

        if ("infobip".equalsIgnoreCase(provider)) {
            return infobip(infobipBaseUrl, infobipApiKey, infobipSender);
        }

        throw new IllegalStateException(
                "Unknown app.sms.provider '" + provider + "'. Known values are 'log' and "
                        + "'infobip'. A new provider is a class implementing SmsSender and one "
                        + "more branch in SmsConfig; nothing else in the application changes."
        );
    }

    /**
     * Builds the Infobip sender, refusing to start without its three settings.
     *
     * Each is named individually in the failure, because "SMS is misconfigured"
     * sends somebody reading logs at 2am to the wrong file. The API key is
     * checked for presence and never echoed.
     */
    private SmsSender infobip(String baseUrl, String apiKey, String sender) {

        /*
         * The base URL is genuinely per-account — Infobip issues each customer
         * a personalised host of the form xxxxx.api.infobip.com, shown on their
         * API dashboard. There is no sensible default to fall back to, and
         * guessing one would send every message of a real deployment to a host
         * that rejects its key.
         */
        require(baseUrl, "app.sms.infobip.base-url",
                "Infobip issues a per-account base URL (https://xxxxx.api.infobip.com); "
                        + "it is shown on the API dashboard when you are signed in.");

        require(apiKey, "app.sms.infobip.api-key",
                "Create one under Developer Tools > API keys. Supply it through the "
                        + "environment; it must not be committed.");

        /*
         * Albania, like most of the region, requires an alphanumeric sender ID
         * to be registered before traffic using it is accepted. Defaulting this
         * to something like "MRTAXI" would produce messages rejected by the
         * operator, which is a harder failure to diagnose than not starting.
         */
        require(sender, "app.sms.infobip.sender",
                "The registered sender ID or number messages come from. "
                        + "Alphanumeric sender IDs must be registered with Infobip before use.");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                /*
                 * "App", not "Bearer". Infobip's own scheme, and the mistake is
                 * silent: a Bearer prefix is simply unauthorised, with nothing
                 * in the message to say the scheme was the problem.
                 */
                .defaultHeader(HttpHeaders.AUTHORIZATION, "App " + apiKey)
                .build();

        log.info("SMS provider: Infobip, sending from '{}'", sender);

        return new InfobipSmsSender(client, sender);
    }

    private static void require(String value, String property, String help) {

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "app.sms.provider=infobip requires " + property + ", which is not set. "
                            + help
            );
        }
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
