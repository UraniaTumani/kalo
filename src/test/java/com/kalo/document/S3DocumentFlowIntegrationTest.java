package com.kalo.document;

import com.jayway.jsonpath.JsonPath;
import com.kalo.common.util.LicenseValidity;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.document.service.DocumentStorage;
import com.kalo.document.service.LocalDocumentStorage;
import com.kalo.document.service.S3DocumentStorage;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring MVC/security, PostgreSQL and S3 SDK/HTTP; only the S3 server is emulated. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class S3DocumentFlowIntegrationTest extends AbstractIntegrationTest {
    private static final String BUCKET = "kalo-document-flow-test";
    private static final String PREFIX = "flow/";
    private static S3Client ownedClient;
    private static final byte[] ORIGINAL = "%PDF-1.7\noriginal evidence".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "%PDF-1.7\nrenewed evidence".getBytes(StandardCharsets.UTF_8);
    private static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.10.0"))
            .withEnv("SERVICES", "s3").withEnv("AWS_DEFAULT_REGION", "us-east-1").withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)
                    .forResponsePredicate(body -> body.matches("(?s).*\"s3\"\\s*:\\s*\"(available|running)\".*")))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        OBJECT_STORE.start();
        properties.add("app.documents.provider", () -> "s3");
        properties.add("app.documents.s3.bucket", () -> BUCKET);
        properties.add("app.documents.s3.prefix", () -> PREFIX);
        properties.add("app.documents.s3.region", () -> "us-east-1");
        properties.add("app.documents.s3.path-style", () -> "true");
        properties.add("app.documents.s3.endpoint", () -> endpoint().toString());
    }

    // Replace only the SDK client so no developer/cloud credentials can be resolved.
    // The production S3 configuration still selects and constructs DocumentStorage.
    @TestBean(name = "documentS3Client", methodName = "testClient", enforceOverride = true)
    S3Client client;

    static S3Client testClient() {
        var client = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
        try {
            client.createBucket(request -> request.bucket(BUCKET));
            ownedClient = client;
            return client;
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    private static URI endpoint() {
        try { return new URI("http", null, OBJECT_STORE.getHost(), OBJECT_STORE.getMappedPort(4566), null, null, null); }
        catch (java.net.URISyntaxException exception) { throw new IllegalStateException(exception); }
    }

    @AfterAll static void stopObjectStore() {
        try { if (ownedClient != null) ownedClient.close(); }
        finally { OBJECT_STORE.stop(); }
    }
    @Autowired DocumentStorage storage;
    @Autowired DocumentRepository documents;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationContext application;

    private record Owner(String base, String adminBase, String partner, String admin, String documentType) {}
    private record Uploaded(long id, String reference) {}

    private Owner owner(DocumentOwnerType type) throws Exception {
        var company = fixtures.approvedCompany();
        Long id = type == DocumentOwnerType.DRIVER
                ? fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE).getId()
                : fixtures.vehicle(company, VehicleStatus.ACTIVE).getId();
        return new Owner("/api/v1/partner/" + (type == DocumentOwnerType.DRIVER ? "drivers/" : "vehicles/") + id + "/documents",
                "/api/v1/admin/partners/" + company.getId() + "/fleet/" + type + "/" + id + "/documents",
                bearer(tokenFor(company.getOwner())), bearer(tokenFor(fixtures.user(UserRole.ADMIN, UserStatus.ACTIVE))),
                type == DocumentOwnerType.DRIVER ? "DRIVING_LICENSE" : "INSURANCE");
    }

    private MockMultipartHttpServletRequestBuilder request(Owner owner, String path, byte[] bytes) {
        var request = multipart(owner.base() + path);
        request.file(new MockMultipartFile("file", "untrusted-name.png", "image/png", bytes));
        request.param("documentType", owner.documentType())
                .param("issuedAt", LicenseValidity.today().toString())
                .param("expiresAt", LicenseValidity.today().plusYears(1).toString());
        return request;
    }

    private Uploaded upload(Owner owner) throws Exception {
        String body = mockMvc.perform(request(owner, "/upload", ORIGINAL).header("Authorization", owner.partner()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verificationStatus").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return uploaded(body);
    }

    private Uploaded renew(Owner owner, Uploaded old) throws Exception {
        String body = mockMvc.perform(request(owner, "/" + old.id() + "/renew", REPLACEMENT).header("Authorization", owner.partner()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return uploaded(body);
    }

    private Uploaded uploaded(String body) {
        String reference = JsonPath.read(body, "$.fileUrl");
        assertThat(reference).matches("s3:[0-9a-f-]{36}\\.pdf");
        return new Uploaded(((Number) JsonPath.read(body, "$.id")).longValue(), reference);
    }

    private void reject(Owner owner, Uploaded old) throws Exception {
        mockMvc.perform(post(owner.adminBase() + "/" + old.id() + "/reject").header("Authorization", owner.admin())
                .contentType("application/json").content("{\"reason\":\"Please replace evidence\"}"))
                .andExpect(status().isOk());
    }

    private Set<String> keys() {
        return client.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(PREFIX)).contents().stream()
                .map(object -> object.key()).collect(Collectors.toSet());
    }

    private void objectExists(Uploaded upload, byte[] expected) {
        assertThat(client.getObjectAsBytes(request -> request.bucket(BUCKET).key(PREFIX + upload.reference().substring(3)))
                .asByteArray()).isEqualTo(expected);
    }

    private void objectMissing(Uploaded upload) {
        assertThatThrownBy(() -> client.headObject(request -> request.bucket(BUCKET).key(PREFIX + upload.reference().substring(3))))
                .isInstanceOfSatisfying(S3Exception.class, exception -> assertThat(exception.statusCode()).isEqualTo(404));
    }

    @Test void productionStorageBeanIsS3WithoutLocalFallback() {
        assertThat(storage).isInstanceOf(S3DocumentStorage.class);
        assertThat(application.getBeansOfType(DocumentStorage.class)).hasSize(1);
        assertThat(application.getBeansOfType(LocalDocumentStorage.class)).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = DocumentOwnerType.class, names = {"DRIVER", "VEHICLE"})
    void uploadReviewRenewAndDownloadPreserveBothVersions(DocumentOwnerType type) throws Exception {
        var owner = owner(type);
        var original = upload(owner);
        objectExists(original, ORIGINAL);
        mockMvc.perform(get(owner.base() + "/" + original.id() + "/file").header("Authorization", owner.partner()))
                .andExpect(status().isOk()).andExpect(content().bytes(ORIGINAL))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
        reject(owner, original);
        var replacement = renew(owner, original);
        assertThat(replacement.reference()).isNotEqualTo(original.reference());
        mockMvc.perform(post(owner.adminBase() + "/" + replacement.id() + "/approve").header("Authorization", owner.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByUserId").isNumber()).andExpect(jsonPath("$.reviewedAt").isNotEmpty());
        var saved = documents.findById(replacement.id()).orElseThrow();
        assertThat(saved.getReviewedByUserId()).isNotNull();
        assertThat(saved.getReviewedAt()).isNotNull();
        mockMvc.perform(get(owner.base()).header("Authorization", owner.partner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(replacement.id()));
        mockMvc.perform(get(owner.base() + "/history").header("Authorization", owner.partner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        for (var version : new Uploaded[]{original, replacement}) {
            byte[] bytes = version == original ? ORIGINAL : REPLACEMENT;
            mockMvc.perform(get(owner.base() + "/" + version.id() + "/file").header("Authorization", owner.partner()))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes));
            mockMvc.perform(get(owner.adminBase() + "/" + version.id() + "/file").header("Authorization", owner.admin()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().bytes(bytes));
            objectExists(version, bytes);
        }
    }

    @ParameterizedTest @EnumSource(value = DocumentOwnerType.class, names = {"DRIVER", "VEHICLE"})
    void foreignPartnerAndWrongRoleCannotReadOrWriteEvidence(DocumentOwnerType type) throws Exception {
        var owner = owner(type);
        var original = upload(owner);
        reject(owner, original);
        var before = keys();
        String foreign = bearer(tokenFor(fixtures.approvedCompany().getOwner()));
        String customer = bearer(tokenFor(fixtures.user(UserRole.CUSTOMER, UserStatus.ACTIVE)));
        mockMvc.perform(get(owner.base() + "/" + original.id() + "/file").header("Authorization", foreign))
                .andExpect(status().isNotFound());
        mockMvc.perform(request(owner, "/" + original.id() + "/renew", REPLACEMENT).header("Authorization", foreign))
                .andExpect(status().isNotFound());
        mockMvc.perform(request(owner, "/upload", REPLACEMENT).header("Authorization", foreign))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(owner.base() + "/" + original.id() + "/file").header("Authorization", customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(owner.adminBase() + "/" + original.id() + "/approve").header("Authorization", owner.partner()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(owner.base() + "/" + original.id() + "/file")).andExpect(status().isUnauthorized());
        assertThat(keys()).isEqualTo(before);
        assertThat(documents.count()).isEqualTo(1);
        objectExists(original, ORIGINAL);
    }

    @ParameterizedTest @EnumSource(value = DocumentOwnerType.class, names = {"DRIVER", "VEHICLE"})
    void uploadRollbackRemovesRowAndObject(DocumentOwnerType type) throws Exception {
        var owner = owner(type);
        var before = keys();
        Uploaded rolledBack = transactions.execute(transaction -> {
            try {
                var uploaded = upload(owner);
                objectExists(uploaded, ORIGINAL);
                assertThat(documents.count()).isEqualTo(1);
                transaction.setRollbackOnly();
                return uploaded;
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        assertThat(rolledBack).isNotNull();
        assertThat(documents.count()).isZero();
        objectMissing(rolledBack);
        assertThat(keys()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = DocumentOwnerType.class, names = {"DRIVER", "VEHICLE"})
    void renewalRollbackRemovesOnlyReplacement(DocumentOwnerType type) throws Exception {
        var owner = owner(type);
        var original = upload(owner);
        reject(owner, original);
        var before = keys();
        Uploaded rolledBack = transactions.execute(transaction -> {
            try {
                var replacement = renew(owner, original);
                objectExists(replacement, REPLACEMENT);
                assertThat(documents.count()).isEqualTo(2);
                transaction.setRollbackOnly();
                return replacement;
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        assertThat(rolledBack).isNotNull();
        assertThat(documents.count()).isEqualTo(1);
        assertThat(documents.findById(original.id()).orElseThrow().getRejectionReason()).isEqualTo("Please replace evidence");
        objectMissing(rolledBack);
        objectExists(original, ORIGINAL);
        assertThat(keys()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = DocumentOwnerType.class, names = {"DRIVER", "VEHICLE"})
    void missingS3EvidenceCannotBeApproved(DocumentOwnerType type) throws Exception {
        var owner = owner(type);
        var original = upload(owner);
        client.deleteObject(request -> request.bucket(BUCKET).key(PREFIX + original.reference().substring(3)));
        mockMvc.perform(post(owner.adminBase() + "/" + original.id() + "/approve").header("Authorization", owner.admin()))
                .andExpect(status().isNotFound());
        var saved = documents.findById(original.id()).orElseThrow();
        assertThat(saved.getVerificationStatus().name()).isEqualTo("DRAFT");
        assertThat(saved.getReviewedByUserId()).isNull();
        assertThat(saved.getReviewedAt()).isNull();
    }
}
