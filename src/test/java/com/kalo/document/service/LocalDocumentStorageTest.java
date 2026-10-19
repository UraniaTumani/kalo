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
    void savesPngUsingDetectedContentRatherThanSuppliedFilenameOrMime() {
        LocalDocumentStorage storage = store("dev");
        byte[] png = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0
        };
        String reference = storage.store(new MockMultipartFile(
                "file", "../../invoice.jpg", "application/pdf", png
        ));
        assertTrue(reference.matches("local:[a-f0-9-]{36}\\.png"));
        var result = storage.read(reference);
        assertArrayEquals(png, result.bytes());
        assertEquals("image/png", result.contentType());
        assertEquals("png", result.extension());
    }

    @Test
    void savesJpegUsingDetectedContentRatherThanSuppliedFilenameOrMime() {
        LocalDocumentStorage storage = store("dev");
        byte[] jpeg = new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0
        };
        String reference = storage.store(new MockMultipartFile(
                "file", "wrong.png", "image/png", jpeg
        ));
        assertTrue(reference.matches("local:[a-f0-9-]{36}\\.jpg"));
        var result = storage.read(reference);
        assertArrayEquals(jpeg, result.bytes());
        assertEquals("image/jpeg", result.contentType());
        assertEquals("jpg", result.extension());
    }

    @Test
    void rejectsTruncatedPngAndJpegSignatures() {
        LocalDocumentStorage storage = store("dev");
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "image.png", "image/png",
                        new byte[] {(byte) 0x89, 'P', 'N', 'G'})
        ));
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "image.jpg", "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8})
        ));
    }

    @Test
    void rejectsEmptyUpload() {
        LocalDocumentStorage storage = store("dev");
        assertThrows(InvalidOperationException.class, () -> storage.store(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])
        ));
    }

    @Test
    void missingLocalFileCannotBeDownloaded() {
        LocalDocumentStorage storage = store("test");
        assertThrows(ResourceNotFoundException.class, () -> storage.read(
                "local:00000000-0000-0000-0000-000000000000.pdf"
        ));
    }

    @Test
    void discardRemovesOnlyTheGeneratedFile() {
        LocalDocumentStorage storage = store("dev");
        String reference = storage.store(new MockMultipartFile(
                "file", "proof.pdf", "application/pdf",
                "%PDF-1.7\nproof".getBytes(StandardCharsets.UTF_8)
        ));
        assertNotNull(storage.read(reference));
        storage.discard(reference);
        assertThrows(ResourceNotFoundException.class, () -> storage.read(reference));
        // A nonexistent generated key is safe to discard as well.
        storage.discard(reference);
        assertThrows(ResourceNotFoundException.class,
                () -> storage.discard("local:../../outside.pdf"));
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
