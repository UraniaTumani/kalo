package com.kalo.document.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Private document bytes; authorization belongs to the calling domain service.
 * Implementations enforce the size/content policy and return opaque references,
 * never client filenames or public download URLs. Reads use stored references,
 * not arbitrary URLs. Local storage remains restricted to development/test.
 */
public interface DocumentStorage {
    long MAX_FILE_BYTES = 5L * 1024 * 1024;

    String store(MultipartFile file);

    StoredDocument read(String reference);

    /** Best-effort cleanup after rollback/deletion; must not mask the original failure. */
    void discard(String reference);

    record StoredDocument(byte[] bytes, String contentType, String extension) {}
}
