package com.kalo.geocoding.config;

import com.kalo.geocoding.service.GeocodingProvider;
import com.kalo.geocoding.service.NominatimGeocodingProvider;
import com.kalo.geocoding.service.StaticGeocodingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Chooses the geocoding provider from configuration.
 *
 * Adding Google Places or Mapbox is a new class implementing GeocodingProvider
 * and one more branch here — nothing else in the application has to know, which
 * was the point of putting the interface on this side of the network.
 */
@Configuration
public class GeocodingConfig {

    /**
     * Short on purpose. A slow provider must not hold a request thread while
     * somebody types the next letter; a timeout becomes an empty suggestion
     * list, which the page already handles, and booking still works from a map
     * tap either way.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public GeocodingProvider geocodingProvider(
            @Value("${app.geocoding.provider:nominatim}") String provider,
            @Value("${app.geocoding.nominatim.base-url:https://nominatim.openstreetmap.org}")
            String nominatimBaseUrl,
            @Value("${app.geocoding.user-agent:KALO/1.0 (taxi marketplace; contact: ops@kalo.al)}")
            String userAgent
    ) {

        if ("static".equalsIgnoreCase(provider)) {
            return new StaticGeocodingProvider();
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        /*
         * Nominatim's policy requires a User-Agent identifying the application
         * and a way to reach whoever runs it. A browser cannot set that header,
         * which is one more reason this call belongs on the server.
         */
        RestClient client = RestClient.builder()
                .baseUrl(nominatimBaseUrl)
                .requestFactory(requestFactory)
                .build();

        return new NominatimGeocodingProvider(client, userAgent);
    }
}
