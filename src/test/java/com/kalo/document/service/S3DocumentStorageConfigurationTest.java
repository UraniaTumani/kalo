package com.kalo.document.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class S3DocumentStorageConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalDocumentStorage.class, S3DocumentStorageConfiguration.class);

    @Test void localRemainsTheDefaultWithoutCreatingS3Client() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DocumentStorage.class).hasSingleBean(LocalDocumentStorage.class);
            assertThat(context).doesNotHaveBean(software.amazon.awssdk.services.s3.S3Client.class);
        });
    }

    @Test void s3OptInSelectsExactlyOneStorageProvider() {
        runner.withPropertyValues("app.documents.provider=s3", "app.documents.s3.bucket=kalo-documents-test",
                "app.documents.s3.region=us-east-1", "app.documents.s3.endpoint=https://objects.example.test")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentStorage.class).doesNotHaveBean(LocalDocumentStorage.class);
                    assertThat(context.getBean(DocumentStorage.class)).isInstanceOf(S3DocumentStorage.class);
                });
    }

    @Test void incompleteS3SettingsFailInsteadOfFallingBackToDisk() {
        runner.withPropertyValues("app.documents.provider=s3").run(context -> assertThat(context).hasFailed());
    }

    @Test void productionAndMixedProfilesRejectHttpAndUrlCredentials() {
        for (String[] profiles : new String[][]{{"prod"}, {"prod", "dev"}, {"staging"}}) {
            var env = new MockEnvironment(); env.setActiveProfiles(profiles);
            assertThatThrownBy(() -> S3DocumentStorageConfiguration.checkedEndpoint("http://localhost:4566", env))
                    .isInstanceOf(IllegalStateException.class);
        }
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        assertThat(S3DocumentStorageConfiguration.checkedEndpoint("http://localhost:4566", env).getHost()).isEqualTo("localhost");
        for (String url : new String[]{"https://user:password@example.test", "https://example.test/path", "https://example.test?token=secret", "file:///tmp"})
            assertThatThrownBy(() -> S3DocumentStorageConfiguration.checkedEndpoint(url, env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void bucketAndPrefixCannotBecomeArbitraryObjectPaths() {
        assertThatThrownBy(() -> S3DocumentStorage.validateLocation("https://example.test", "documents/"))
                .isInstanceOf(IllegalStateException.class);
        for (String prefix : new String[]{"", "../", "/documents/", "documents/../", "documents"})
            assertThatThrownBy(() -> S3DocumentStorage.validateLocation("kalo-documents-test", prefix))
                    .isInstanceOf(IllegalStateException.class);
    }
}
