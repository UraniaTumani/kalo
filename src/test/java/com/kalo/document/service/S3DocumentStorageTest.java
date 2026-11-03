package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3DocumentStorageTest {
    private final S3Client client = mock(S3Client.class);
    private final DocumentStorage storage = new S3DocumentStorage(client, "kalo-documents-test", "documents/");

    @BeforeEach void setup() {
        lenient().when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().serverSideEncryption(ServerSideEncryption.AES256).build());
    }

    @Test void uploadsValidatedBytesWithOpaqueKeyEncryptionAndNoOverwrite() throws Exception {
        byte[] bytes = "%PDF-1.7\nprivate test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String reference = storage.store(new MockMultipartFile("file", "../../personal-name.png", "image/png", bytes));
        assertThat(reference).matches("s3:[a-f0-9-]{36}\\.pdf").doesNotContain("personal-name", "kalo-documents-test");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().key()).isEqualTo("documents/" + reference.substring(3));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.getValue().acl()).isNull();
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(request.getValue().cacheControl()).isEqualTo("no-store");
        try (var input = body.getValue().contentStreamProvider().newStream()) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
    }

    @Test void invalidEmptyAndOversizedBytesNeverReachProvider() {
        for (byte[] bytes : new byte[][]{new byte[0], "<html>bad</html>".getBytes(), new byte[(int) DocumentStorage.MAX_FILE_BYTES + 1]}) {
            assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "proof.pdf", "application/pdf", bytes)))
                    .isInstanceOf(InvalidOperationException.class);
        }
        verifyNoInteractions(client);
    }

    @Test void understatedMultipartSizeStillUsesBoundedRead() {
        var dishonest = new MockMultipartFile("file", "proof.pdf", "application/pdf", new byte[(int) DocumentStorage.MAX_FILE_BYTES + 1]) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> storage.store(dishonest)).isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(client);
    }

    @Test void providerErrorsUseSafeMessageAndNeverDeleteAfterFailedPut() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("PRIVATE_PROVIDER_DETAIL").build());
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "%PDF-1.7".getBytes())))
                .isInstanceOf(DocumentStorageUnavailableException.class).hasMessage("Document storage is temporarily unavailable");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void missingEncryptionConfirmationIsNotReportedAsSuccess() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "%PDF-1.7".getBytes())))
                .isInstanceOf(DocumentStorageUnavailableException.class);
    }

    @Test void foreignLocalUrlAndTraversalReferencesNeverReachProvider() {
        for (String reference : new String[]{null, "local:00000000-0000-0000-0000-000000000000.pdf", "https://example.test/a.pdf", "s3:../../a.pdf", "s3:00000000-0000-0000-0000-000000000000.gif"}) {
            assertThatThrownBy(() -> storage.read(reference)).isInstanceOf(ResourceNotFoundException.class);
            storage.discard(reference);
        }
        verifyNoInteractions(client);
    }

    @Test void cleanupIsBoundedToGeneratedReferenceAndDoesNotMaskFailure() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("PRIVATE_PROVIDER_DETAIL"));
        assertThatCode(() -> storage.discard("s3:00000000-0000-0000-0000-000000000000.pdf")).doesNotThrowAnyException();
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("documents/00000000-0000-0000-0000-000000000000.pdf");
    }

    @Test void providerNotFoundDiffersFromPermissionFailure() {
        doThrow(NoSuchKeyException.builder().statusCode(404).build()).when(client)
                .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
        assertThatThrownBy(() -> storage.read("s3:00000000-0000-0000-0000-000000000000.pdf"))
                .isInstanceOf(ResourceNotFoundException.class);
        doThrow(S3Exception.builder().statusCode(403).message("PRIVATE_PROVIDER_DETAIL").build()).when(client)
                .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
        assertThatThrownBy(() -> storage.read("s3:00000000-0000-0000-0000-000000000000.pdf"))
                .isInstanceOf(DocumentStorageUnavailableException.class).hasMessage("Document storage is temporarily unavailable");
    }
}
