package com.kalo.document.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.documents.provider", havingValue = "s3")
public class S3DocumentStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client documentS3Client(@Value("${app.documents.s3.bucket:}") String bucket,
            @Value("${app.documents.s3.prefix:documents/}") String prefix,
            @Value("${app.documents.s3.region:}") String region,
            @Value("${app.documents.s3.endpoint:}") String endpoint,
            @Value("${app.documents.s3.path-style:false}") boolean pathStyle, Environment environment) {
        S3DocumentStorage.validateLocation(bucket, prefix);
        if (region == null || !region.matches("[a-z0-9-]{1,40}"))
            throw new IllegalStateException("A document S3 region is required");
        var builder = S3Client.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .apiCallTimeout(Duration.ofSeconds(20))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()));
        // No credential values in application configuration: SDK default credentials chain.
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(checkedEndpoint(endpoint, environment));
        return builder.build();
    }

    @Bean
    DocumentStorage s3DocumentStorage(@org.springframework.beans.factory.annotation.Qualifier("documentS3Client") S3Client documentS3Client,
            @Value("${app.documents.s3.bucket:}") String bucket,
            @Value("${app.documents.s3.prefix:documents/}") String prefix) {
        return new S3DocumentStorage(documentS3Client, bucket, prefix);
    }

    static URI checkedEndpoint(String value, Environment environment) {
        final URI endpoint;
        try { endpoint = URI.create(value); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("Invalid document S3 endpoint"); }
        boolean development = environment.acceptsProfiles(Profiles.of("dev", "test"))
                && !environment.acceptsProfiles(Profiles.of("prod"));
        boolean scheme = "https".equals(endpoint.getScheme()) || development && "http".equals(endpoint.getScheme());
        if (!scheme || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty() && !"/".equals(endpoint.getRawPath()))
            throw new IllegalStateException("Document S3 endpoint must be an HTTPS origin; HTTP is dev/test-only");
        return endpoint;
    }
}
