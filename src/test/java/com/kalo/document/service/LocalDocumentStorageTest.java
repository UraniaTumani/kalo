package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocumentStorageTest {

    @TempDir
    Path tempDir;

    private LocalDocumentStorage store(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new LocalDocumentStorage(tempDir.toString(), environment);
    }

    @Test
    void savesValidPdfWithOpaqueNameAndReadsBack() {
        LocalDocumentStorage storage = store("dev");
        byte[] bytes = "%PDF-1.7\nhello".getBytes(StandardCharsets.UTF_8);
        String reference = storage.store(new MockMultipartFile(
                "file", "../../secret.pdf", "image/png", bytes
        ));
        assertTrue(reference.matches("local:[a-f0-9-]{36}\\.pdf"));
        assertFalse(reference.contains("secret"));
        var result = storage.read(reference);
        assertArrayEquals(bytes, result.bytes());
        assertEquals("application/pdf", result.contentType());
    }

    @Test
    void rejectsInvalidFileEvenIfExtensionAndMimeLookSafe() {
        LocalDocumentStorage storage = store("test");
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "safe.pdf", "application/pdf",
                        "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Test
    void rejectsOversizedUpload() {
        LocalDocumentStorage storage = store("dev");
        byte[] bytes = new byte[(int) LocalDocumentStorage.MAX_FILE_BYTES + 1];
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "large.pdf", "application/pdf", bytes)
        ));
    }

    @Test
    void rejectsPathTraversalAndUnknownFiles() {
        LocalDocumentStorage storage = store("dev");
        assertThrows(ResourceNotFoundException.class,
                () -> storage.read("local:../../secret.pdf"));
        assertThrows(ResourceNotFoundException.class,
                () -> storage.read("https://example.com/old.pdf"));
    }

    @Test
    void productionCannotUseEphemeralLocalStorage() {
        LocalDocumentStorage storage = store("prod");
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "safe.pdf", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8))
        ));
        assertThrows(InvalidOperationException.class,
                () -> storage.read("local:00000000-0000-0000-0000-000000000000.pdf"));
    }
}
