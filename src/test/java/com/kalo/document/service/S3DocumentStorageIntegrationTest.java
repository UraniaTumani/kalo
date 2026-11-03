package com.kalo.document.service;

import com.kalo.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Actual SDK/HTTP against a disposable S3 emulator; no cloud account or real keys. */
@Testcontainers
class S3DocumentStorageIntegrationTest {
    @Container
    static final GenericContainer<?> objectStore = new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.10.0"))
            .withEnv("SERVICES", "s3").withEnv("AWS_DEFAULT_REGION", "us-east-1").withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)
                    .forResponsePredicate(body -> body.matches("(?s).*\"s3\"\\s*:\\s*\"(available|running)\".*")))
            .withStartupTimeout(Duration.ofMinutes(2));
    static final String BUCKET = "kalo-document-storage-test";
    static S3Client client;

    static S3Client newClient() throws Exception {
        return S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(new URI("http", null, objectStore.getHost(), objectStore.getMappedPort(4566), null, null, null))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
    }

    @BeforeAll static void setup() throws Exception {
        client = newClient();
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }
    @AfterAll static void close() { if (client != null) client.close(); }

    @Test void bytesSurviveClientRecreationWithEncryptionAndConditionalWrites() throws Exception {
        var storage = new S3DocumentStorage(client, BUCKET, "documents/");
        byte[] bytes = "%PDF-1.7\nprivate object\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String reference = storage.store(new MockMultipartFile("file", "private.png", "image/png", bytes));
        String key = "documents/" + reference.substring(3);
        try (var anotherClient = newClient()) {
            var anotherStorage = new S3DocumentStorage(anotherClient, BUCKET, "documents/");
            assertThat(anotherStorage.read(reference).bytes()).isEqualTo(bytes);
            assertThat(anotherStorage.read(reference).contentType()).isEqualTo("application/pdf");
        }
        assertThat(client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()).serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AES256);
        assertThatThrownBy(() -> client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key)
                .ifNoneMatch("*").serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(bytes)))
                .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(412));
        storage.discard(reference);
        assertThatThrownBy(() -> storage.read(reference)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void corruptedAndOversizedObjectsAreNotServed() {
        var storage = new S3DocumentStorage(client, BUCKET, "documents/");
        for (byte[] bytes : new byte[][]{"<html>invalid</html>".getBytes(), new byte[(int) DocumentStorage.MAX_FILE_BYTES + 1]}) {
            String key = UUID.randomUUID() + ".pdf";
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key("documents/"+key)
                    .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(bytes));
            assertThatThrownBy(() -> storage.read("s3:"+key)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test void wrongExtensionAndUnrelatedObjectCannotBeReadThroughReference() {
        var storage = new S3DocumentStorage(client, BUCKET, "documents/");
        String key = UUID.randomUUID() + ".png";
        client.putObject(PutObjectRequest.builder().bucket(BUCKET).key("documents/"+key)
                .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromString("%PDF-1.7\nwrong extension"));
        assertThatThrownBy(() -> storage.read("s3:"+key)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> storage.read("s3:../documents/"+key)).isInstanceOf(ResourceNotFoundException.class);
    }
}
