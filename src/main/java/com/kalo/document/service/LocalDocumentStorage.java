package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Private development-only document store. Never exposes a static URL or the
 * client-supplied filename. Production needs a durable object-storage provider
 * before uploads are enabled; it must not silently use ephemeral container FS.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.documents.provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorage {

    private static final String PREFIX = "local:";
    private final Path root;
    private final Environment environment;

    public LocalDocumentStorage(
            @Value("${app.documents.local-directory:./.local/document-uploads}") String directory,
            Environment environment
    ) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.environment = environment;
    }

    @Override
    public String store(MultipartFile file) {
        requireDevStorage();

        var content = DocumentFilePolicy.upload(file);
        try {
            byte[] bytes = content.bytes();
            String extension = content.extension();

            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new InvalidOperationException("Document storage directory is not safe");
            }

            String key = UUID.randomUUID() + "." + extension;
            Path destination = root.resolve(key);
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return PREFIX + key;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store uploaded document", exception);
        }
    }

    @Override
    public StoredDocument read(String reference) {
        requireDevStorage();
        String key = checkedKey(reference);
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root)) {
            throw new ResourceNotFoundException("Document file not found");
        }
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) {
                throw new ResourceNotFoundException("Document file not found");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new ResourceNotFoundException("Document file not found");
            }
            String extension = DocumentFilePolicy.extension(bytes);
            if (extension == null || !key.endsWith("." + extension)) {
                throw new ResourceNotFoundException("Document file not found");
            }
            String contentType = DocumentFilePolicy.contentType(extension);
            return new StoredDocument(bytes, contentType, extension);
        } catch (IOException exception) {
            throw new ResourceNotFoundException("Document file not found");
        }
    }

    /**
     * Best-effort cleanup for an upload that never committed its database row.
     * Only generated opaque keys in the private directory are accepted.
     */
    @Override
    public void discard(String reference) {
        if (!isLocalReference(reference)) {
            return;
        }
        String key = checkedKey(reference);
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Do not mask the original database failure.
        }
    }

    public boolean isLocalReference(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    private void requireDevStorage() {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new InvalidOperationException(
                    "Direct uploads require configured durable storage outside development"
            );
        }
    }

    private String checkedKey(String reference) {
        if (!isLocalReference(reference)) {
            throw new ResourceNotFoundException("This document uses an external link");
        }
        String key = reference.substring(PREFIX.length());
        if (!key.matches("[a-f0-9-]{36}\\.(pdf|jpg|png)")) {
            throw new ResourceNotFoundException("Document file not found");
        }
        return key;
    }

}
