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
public class LocalDocumentStorage {

    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
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

    public String store(MultipartFile file) {
        requireDevStorage();

        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new InvalidOperationException("Choose a PDF, JPG or PNG file of at most 5 MB");
        }

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new InvalidOperationException("Document exceeds the 5 MB limit");
            }

            // Inspect the actual bytes, never trust a browser-supplied MIME type
            // or extension. All files are served as attachments, with nosniff.
            String extension = detectExtension(bytes);
            if (extension == null) {
                throw new InvalidOperationException("Only PDF, JPG and PNG documents are accepted");
            }

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
            String extension = detectExtension(bytes);
            if (extension == null || !key.endsWith("." + extension)) {
                throw new ResourceNotFoundException("Document file not found");
            }
            String contentType = switch (extension) {
                case "pdf" -> "application/pdf";
                case "png" -> "image/png";
                default -> "image/jpeg";
            };
            return new StoredDocument(bytes, contentType, extension);
        } catch (IOException exception) {
            throw new ResourceNotFoundException("Document file not found");
        }
    }

    public boolean isLocalReference(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    private void requireDevStorage() {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
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

    private String detectExtension(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P'
                && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "pdf";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P'
                && b[2] == 'N' && b[3] == 'G' && (b[4] & 0xff) == 13
                && (b[5] & 0xff) == 10 && (b[6] & 0xff) == 26
                && (b[7] & 0xff) == 10) {
            return "png";
        }
        if (b.length >= 4 && (b[0] & 0xff) == 0xff
                && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "jpg";
        }
        return null;
    }

    public record StoredDocument(byte[] bytes, String contentType, String extension) {}
}
