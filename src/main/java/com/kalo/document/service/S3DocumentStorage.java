package com.kalo.document.service;

import com.kalo.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.UUID;

/** S3 API adapter. Bucket privacy and durable infrastructure are deployment requirements. */
public final class S3DocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);
    private static final String REFERENCE_PREFIX = "s3:";
    private static final String KEY_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(pdf|jpg|png)";
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3DocumentStorage(S3Client client, String bucket, String prefix) {
        validateLocation(bucket, prefix);
        this.client = java.util.Objects.requireNonNull(client);
        this.bucket = bucket;
        this.prefix = prefix;
    }

    static void validateLocation(String bucket, String prefix) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains(".."))
            throw new IllegalStateException("A valid document S3 bucket is required");
        if (prefix == null || prefix.length() > 128 || !prefix.matches("(?:[A-Za-z0-9_-]+/)+"))
            throw new IllegalStateException("A safe document S3 key prefix ending in / is required");
    }

    @Override
    public String store(org.springframework.web.multipart.MultipartFile file) {
        var content = DocumentFilePolicy.upload(file);
        String key = UUID.randomUUID() + "." + content.extension();
        try {
            var response = client.putObject(PutObjectRequest.builder().bucket(bucket).key(prefix + key)
                    .ifNoneMatch("*").contentLength((long) content.bytes().length)
                    .contentType(DocumentFilePolicy.contentType(content.extension()))
                    .contentDisposition("attachment").cacheControl("no-store")
                    .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(content.bytes()));
            if (response.serverSideEncryption() != ServerSideEncryption.AES256) {
                log.warn("Document storage did not confirm requested server-side encryption");
                throw new DocumentStorageUnavailableException();
            }
            return REFERENCE_PREFIX + key;
        } catch (SdkException exception) {
            // A failed reply can leave an unreferenced object. Do not risk deleting
            // a pre-existing object after a failed conditional PUT; reconcile separately.
            log.warn("Document storage upload failed or its outcome could not be confirmed");
            throw new DocumentStorageUnavailableException();
        }
    }

    private record Payload(GetObjectResponse metadata, byte[] bytes) {}

    @Override
    public StoredDocument read(String reference) {
        String key = checkedKey(reference);
        final Payload payload;
        try {
            payload = client.getObject(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build(),
                    (metadata, input) -> {
                        if (metadata.contentLength() != null && metadata.contentLength() > MAX_FILE_BYTES) {
                            input.abort(); return new Payload(metadata, new byte[0]);
                        }
                        byte[] bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
                        if (bytes.length > MAX_FILE_BYTES) input.abort();
                        return new Payload(metadata, bytes);
                    });
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) throw missing();
            log.warn("Document storage read failed");
            throw new DocumentStorageUnavailableException();
        } catch (SdkException exception) {
            log.warn("Document storage read failed");
            throw new DocumentStorageUnavailableException();
        }
        byte[] bytes = payload.bytes();
        String extension = DocumentFilePolicy.extension(bytes);
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || extension == null || !key.endsWith("." + extension)
                || payload.metadata().contentLength() == null || payload.metadata().contentLength() != bytes.length
                || payload.metadata().serverSideEncryption() != ServerSideEncryption.AES256) throw missing();
        return new StoredDocument(bytes, DocumentFilePolicy.contentType(extension), extension);
    }

    @Override
    public void discard(String reference) {
        if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) return;
        String key = reference.substring(REFERENCE_PREFIX.length());
        if (!key.matches(KEY_PATTERN)) return;
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
        } catch (RuntimeException exception) {
            log.warn("Document storage cleanup failed; reconciliation may be required");
        }
    }

    private String checkedKey(String reference) {
        if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) throw missing();
        String key = reference.substring(REFERENCE_PREFIX.length());
        if (!key.matches(KEY_PATTERN)) throw missing();
        return key;
    }

    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Document file not found"); }
}
